package com.knowimran.rivoquery.platform.secrets;

import com.knowimran.rivoquery.connection.application.secret.BoundSecretReference;
import com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import com.knowimran.rivoquery.connection.application.secret.StagedSecretReference;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Owns every filesystem detail of the local ciphertext-only envelope store:
 * directory layout/permissions, create-only atomic writes, idempotent
 * staging, tombstoned revocation, and bounded temp-file/tombstone cleanup.
 *
 * <p>Deliberately does <em>not</em> implement {@code SecretStore} and knows
 * nothing about grants - {@link LocalAesGcmSecretStore} is the sole
 * {@code SecretStore} entry point and performs all grant/binding validation
 * before delegating here with already-validated, plain values. This split
 * also keeps every one of this class's methods reachable only through a
 * class the whole-codebase "only the authority calls {@code SecretStore}"
 * rule does not (and must not) match on.
 */
final class LocalSecretStorage {
    private static final int FORMAT_VERSION = 1;
    private static final Duration TEMP_FILE_MAX_AGE = Duration.ofHours(1);
    private static final EnumSet<PosixFilePermission> OWNER_ONLY_DIRECTORY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final EnumSet<PosixFilePermission> OWNER_ONLY_FILE =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path activeDirectory;
    private final Path revokedDirectory;
    private final Path tempDirectory;
    private final KeyEncryptionKeyRing keyRing;
    private final SecretReferenceKey referenceKey;

    LocalSecretStorage(Path storageDirectory, KeyEncryptionKeyRing keyRing, SecretReferenceKey referenceKey) {
        Objects.requireNonNull(storageDirectory, "storageDirectory");
        this.keyRing = Objects.requireNonNull(keyRing, "keyRing");
        this.referenceKey = Objects.requireNonNull(referenceKey, "referenceKey");
        this.activeDirectory = storageDirectory.resolve("active");
        this.revokedDirectory = storageDirectory.resolve("revoked");
        this.tempDirectory = storageDirectory.resolve("tmp");
        createOwnerOnlyDirectory(storageDirectory);
        createOwnerOnlyDirectory(activeDirectory);
        createOwnerOnlyDirectory(revokedDirectory);
        createOwnerOnlyDirectory(tempDirectory);
    }

    StagedSecretReference stage(
            String providerNamespace, SecretBinding binding, DestructibleSecretMaterial material, String operationId) {
        String opaqueId = referenceKey.opaqueId(binding, operationId);
        Path target = activeDirectory.resolve(fileName(opaqueId));
        String bindingFingerprint = SecretBindingCodec.fingerprint(binding);

        Objects.requireNonNull(material, "material").consume(bytes -> {
            writeEnvelopeIfAbsent(target, opaqueId, binding, bindingFingerprint, bytes);
            return null;
        });

        var reference = new SecretReference(providerNamespace, opaqueId, binding.version(), bindingFingerprint);
        return new StagedSecretReference(new BoundSecretReference(binding, reference));
    }

    byte[] resolvePlaintext(SecretReference reference, SecretBinding binding) {
        LocalSecretEnvelope envelope = readEnvelope(activeDirectory.resolve(fileName(reference.opaqueId())));
        verifyEnvelopeIdentity(envelope, reference);
        return decryptEnvelope(envelope, binding);
    }

    void revoke(SecretReference reference) {
        Path active = activeDirectory.resolve(fileName(reference.opaqueId()));
        if (!Files.exists(active)) {
            return;
        }
        LocalSecretEnvelope envelope = readEnvelope(active);
        verifyEnvelopeIdentity(envelope, reference);
        Path tombstone = revokedDirectory.resolve(fileName(reference.opaqueId()));
        try {
            Files.move(active, tombstone, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (NoSuchFileException raceLost) {
            return;
        } catch (IOException e) {
            // Files.move's own IOException message routinely embeds the
            // absolute source/target paths, which for this adapter contain
            // the opaque secret ID; redact before it can be surfaced.
            throw SecretStorageUnavailableException.redacting(e);
        }
        deleteQuietly(tombstone);
    }

    /**
     * Deletes create-only temporary files older than a bounded age that were
     * never linked into {@code active/} because the process crashed between
     * writing and linking. Safe to call repeatedly; not yet scheduled by any
     * framework component (deferred to the operational wiring that enables
     * this adapter).
     */
    int cleanupOrphanedTemporaryFiles() {
        int cleaned = 0;
        try (var entries = Files.list(tempDirectory)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (isOlderThan(entry, TEMP_FILE_MAX_AGE) && deleteQuietly(entry)) {
                    cleaned++;
                }
            }
        } catch (IOException e) {
            // Files.list's own IOException message routinely embeds the
            // absolute directory path; redact before it can be surfaced.
            throw SecretStorageUnavailableException.redacting(e);
        }
        return cleaned;
    }

    /**
     * Deletes tombstoned envelopes in {@code revoked/} that were already
     * moved out of {@code active/} (and are therefore already unavailable to
     * resolution) but not yet physically deleted, for example because the
     * process crashed between the tombstone move and the delete in
     * {@link #revoke}. Safe to call repeatedly; not yet scheduled by any
     * framework component.
     */
    int cleanupRevokedTombstones() {
        int cleaned = 0;
        try (var entries = Files.list(revokedDirectory)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (deleteQuietly(entry)) {
                    cleaned++;
                }
            }
        } catch (IOException e) {
            // Files.list's own IOException message routinely embeds the
            // absolute directory path; redact before it can be surfaced.
            throw SecretStorageUnavailableException.redacting(e);
        }
        return cleaned;
    }

    private void writeEnvelopeIfAbsent(
            Path target,
            String opaqueId,
            SecretBinding binding,
            String bindingFingerprint,
            byte[] plaintext) {
        Path temp = tempDirectory.resolve(fileName("staging-" + UUID.randomUUID()));
        byte[] dataEncryptionKey = AesGcmCipher.randomBytes(AesGcmCipher.KEY_LENGTH_BYTES);
        try {
            createOwnerOnlyFile(temp);
            byte[] materialNonce = AesGcmCipher.randomBytes(AesGcmCipher.NONCE_LENGTH_BYTES);
            byte[] additionalData = SecretBindingCodec.additionalData(FORMAT_VERSION, binding);
            byte[] ciphertext = AesGcmCipher.encrypt(dataEncryptionKey, materialNonce, plaintext, additionalData);
            var wrapped = keyRing.wrap(dataEncryptionKey, wrapAdditionalData(opaqueId, bindingFingerprint));
            var envelope = new LocalSecretEnvelope(
                    FORMAT_VERSION,
                    opaqueId,
                    wrapped.keyId(),
                    wrapped.nonce(),
                    wrapped.ciphertext(),
                    materialNonce,
                    bindingFingerprint,
                    ciphertext);
            SecretEnvelopeCodec.write(temp, envelope);
            fsync(temp);
            try {
                Files.createLink(target, temp);
            } catch (FileAlreadyExistsException alreadyStaged) {
                // A concurrent/replayed stage() for the exact same
                // (binding, operationId) won the create-only race. Never
                // persist a plaintext-derived value to distinguish a true
                // replay from a conflicting reuse (see SecretReferenceKey's
                // Javadoc): decrypt the envelope that won the race through
                // the same DEK-unwrap path used by resolve, and compare the
                // recovered plaintext to the incoming plaintext in memory,
                // constant-time, zeroing both immediately afterward.
                requireIdenticalMaterial(target, binding, plaintext);
            }
            fsyncDirectory(activeDirectory);
        } catch (IOException e) {
            // May originate from Files.createFile(temp) or Files.createLink,
            // whose IOException message routinely embeds the absolute
            // staging/target envelope path (and therefore the opaque secret
            // ID); redact before it can be surfaced.
            throw SecretStorageUnavailableException.redacting(e);
        } finally {
            Arrays.fill(dataEncryptionKey, (byte) 0);
            deleteQuietly(temp);
        }
    }

    /**
     * Invoked only after losing the create-only race in
     * {@link #writeEnvelopeIfAbsent}. Treats an exact plaintext match as an
     * idempotent replay (returns normally) and any mismatch as a genuine
     * conflict ({@link SecretStagingConflictException}), fail-closed.
     */
    private void requireIdenticalMaterial(Path target, SecretBinding binding, byte[] incomingPlaintext) {
        LocalSecretEnvelope existingEnvelope = readEnvelope(target);
        byte[] existingPlaintext = decryptEnvelope(existingEnvelope, binding);
        try {
            if (!MessageDigest.isEqual(existingPlaintext, incomingPlaintext)) {
                throw new SecretStagingConflictException();
            }
        } finally {
            Arrays.fill(existingPlaintext, (byte) 0);
        }
    }

    private byte[] decryptEnvelope(LocalSecretEnvelope envelope, SecretBinding binding) {
        byte[] additionalData = SecretBindingCodec.additionalData(FORMAT_VERSION, binding);
        byte[] dataEncryptionKey = keyRing.unwrap(
                envelope.keyId(),
                envelope.wrapNonce(),
                envelope.wrappedDataKey(),
                wrapAdditionalData(envelope.opaqueId(), envelope.bindingFingerprint()));
        try {
            return AesGcmCipher.decrypt(dataEncryptionKey, envelope.materialNonce(), envelope.ciphertext(), additionalData);
        } finally {
            Arrays.fill(dataEncryptionKey, (byte) 0);
        }
    }

    private static void verifyEnvelopeIdentity(LocalSecretEnvelope envelope, SecretReference reference) {
        if (!envelope.opaqueId().equals(reference.opaqueId())
                || !envelope.bindingFingerprint().equals(reference.bindingFingerprint())) {
            throw new SecretIntegrityException();
        }
    }

    /**
     * A missing envelope and a present-but-wrong-binding envelope must be
     * indistinguishable to a caller: both fail closed as
     * {@link SecretIntegrityException}, never revealing whether the opaque
     * ID exists (see {@link #verifyEnvelopeIdentity}, which raises the same
     * exception for the "present but wrong" case).
     */
    private static LocalSecretEnvelope readEnvelope(Path file) {
        if (!Files.exists(file)) {
            throw new SecretIntegrityException();
        }
        return SecretEnvelopeCodec.read(file);
    }

    private static byte[] wrapAdditionalData(String opaqueId, String bindingFingerprint) {
        byte[] opaqueIdBytes = opaqueId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] fingerprintBytes = bindingFingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] result = new byte[opaqueIdBytes.length + 1 + fingerprintBytes.length];
        System.arraycopy(opaqueIdBytes, 0, result, 0, opaqueIdBytes.length);
        result[opaqueIdBytes.length] = '|';
        System.arraycopy(fingerprintBytes, 0, result, opaqueIdBytes.length + 1, fingerprintBytes.length);
        return result;
    }

    private static String fileName(String opaqueId) {
        return opaqueId + ".secret";
    }

    private static void createOwnerOnlyDirectory(Path directory) {
        try {
            if (Files.exists(directory)) {
                // A pre-existing directory (including one this process did
                // not create) must not be trusted silently - verify it is
                // still owner-only before storing ciphertext beneath it.
                requireOwnerOnlyPermissions(directory);
                return;
            }
            if (supportsPosixPermissions(directory)) {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
            } else {
                Files.createDirectories(directory);
            }
        } catch (IOException e) {
            throw SecretStorageUnavailableException.redacting(e);
        }
    }

    private static void requireOwnerOnlyPermissions(Path directory) throws IOException {
        if (!supportsPosixPermissions(directory)) {
            return;
        }
        Set<PosixFilePermission> actual = Files.getPosixFilePermissions(directory);
        if (!actual.equals(OWNER_ONLY_DIRECTORY)) {
            throw new SecretStorageUnavailableException();
        }
    }

    private static boolean supportsPosixPermissions(Path path) {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static void createOwnerOnlyFile(Path file) throws IOException {
        if (supportsPosixPermissions(file)) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE));
        } else {
            Files.createFile(file);
        }
    }

    private static void fsync(Path file) {
        try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (IOException e) {
            throw SecretStorageUnavailableException.redacting(e);
        }
    }

    private static void fsyncDirectory(Path directory) {
        try (var channel = java.nio.channels.FileChannel.open(directory, java.nio.file.StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException bestEffortUnsupported) {
            // Directory fsync is not supported on every platform/filesystem
            // (e.g. some overlay/test filesystems); this is a best-effort
            // durability step, not a correctness requirement of the
            // create-only-then-link protocol itself.
        }
    }

    private static boolean isOlderThan(Path path, Duration maxAge) {
        try {
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            return modified.isBefore(Instant.now().minus(maxAge));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException ignored) {
            return false;
        }
    }
}
