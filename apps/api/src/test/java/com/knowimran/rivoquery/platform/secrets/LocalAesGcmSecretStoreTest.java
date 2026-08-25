package com.knowimran.rivoquery.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.ConnectionState;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAesGcmSecretStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CANARY_PASSWORD = "unique-canary-password-value";

    @TempDir
    Path directory;

    @Test
    void stageThenConsumeRoundTripsThePlaintextThroughARealAuthorityAndAdapter() {
        var store = newStore(directory);
        var connection = DatabaseConnection.reserve(
                        SecretStoreTestFixtures.WORKSPACE, SecretStoreTestFixtures.CONNECTION, DatabaseKind.POSTGRESQL)
                .reserveNextSecretVersion(new com.knowimran.rivoquery.connection.ConnectionVersion(1));
        var binding = SecretStoreTestFixtures.binding(1);
        var stageAuthority = SecretStoreTestFixtures.authority(
                store, connection, CLOCK, Optional.of(SecretStoreTestFixtures.stageOperation(
                        SecretStoreTestFixtures.STAGE_OPERATION, binding)));

        var staged = stage(stageAuthority, binding, CANARY_PASSWORD);

        var withSecret = connection.attachCurrentSecret(connection.version(), staged.value());
        var active = withSecret.transition(withSecret.version(), ConnectionState.ACTIVE);
        var consumeAuthority = SecretStoreTestFixtures.authority(store, active, CLOCK);

        var consumed = new byte[1][];
        consumeAuthority.consume(
                SecretStoreTestFixtures.actor(),
                SecretStoreTestFixtures.CONNECTION,
                SecretStoreTestFixtures.connectionTest(),
                new Deadline(NOW.plusSeconds(30)),
                bytes -> consumed[0] = bytes.clone());

        assertThat(new String(consumed[0], StandardCharsets.UTF_8)).isEqualTo(CANARY_PASSWORD);
    }

    @Test
    void persistedEnvelopeNeverContainsThePlaintextOnDisk() throws Exception {
        var store = newStore(directory);
        var binding = SecretStoreTestFixtures.binding(1);
        stageOnly(store, binding, SecretStoreTestFixtures.STAGE_OPERATION, CANARY_PASSWORD);

        Path activeDirectory = directory.resolve("active");
        try (var files = Files.list(activeDirectory)) {
            for (Path file : files.toList()) {
                byte[] raw = Files.readAllBytes(file);
                assertThat(new String(raw, StandardCharsets.ISO_8859_1)).doesNotContain(CANARY_PASSWORD);
            }
        }
    }

    @Test
    void twoStagesOfTheSamePlaintextUnderDifferentOperationsProduceDifferentCiphertext() throws Exception {
        var store = newStore(directory);
        var binding1 = SecretStoreTestFixtures.binding(1);
        stageOnly(store, binding1, SecretStoreTestFixtures.STAGE_OPERATION, CANARY_PASSWORD);

        var binding2 = SecretStoreTestFixtures.binding(2);
        var secondOperation = new com.knowimran.rivoquery.connection.OperationId(
                "op_" + SecretStoreTestFixtures.ULID.replace('P', 'R'));
        stageOnly(store, binding2, secondOperation, CANARY_PASSWORD);

        Path activeDirectory = directory.resolve("active");
        var files = Files.list(activeDirectory).toList();
        assertThat(files).hasSize(2);
        byte[] first = Files.readAllBytes(files.get(0));
        byte[] second = Files.readAllBytes(files.get(1));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void replayingTheSameOperationAndBindingWithTheSameMaterialIsIdempotent() {
        var store = newStore(directory);
        var binding = SecretStoreTestFixtures.binding(1);
        var staged1 = stageOnly(store, binding, SecretStoreTestFixtures.STAGE_OPERATION, CANARY_PASSWORD);
        var staged2 = stageOnly(store, binding, SecretStoreTestFixtures.STAGE_OPERATION, CANARY_PASSWORD);

        assertThat(staged1.value()).isEqualTo(staged2.value());
    }

    @Test
    void reusingTheSameOperationAndBindingWithDifferentMaterialFailsClosed() {
        var store = newStore(directory);
        var binding = SecretStoreTestFixtures.binding(1);
        stageOnly(store, binding, SecretStoreTestFixtures.STAGE_OPERATION, CANARY_PASSWORD);

        assertThatThrownBy(() -> stageOnly(
                        store, binding, SecretStoreTestFixtures.STAGE_OPERATION, "a-completely-different-password"))
                .isInstanceOf(SecretStagingConflictException.class)
                .satisfies(ex -> assertNoLeakage(ex, directory, CANARY_PASSWORD));
    }

    @Test
    void resolvingWithADifferentKeyEncryptionKeyFailsIntegrityVerification() {
        var store = newStore(directory);
        var binding = SecretStoreTestFixtures.binding(1);
        var staged = stageOnly(store, binding, SecretStoreTestFixtures.STAGE_OPERATION, CANARY_PASSWORD);

        var wrongKeyStore = new LocalAesGcmSecretStore(
                directory,
                KeyEncryptionKeyRing.of(Map.of("v1", AesGcmCipher.randomBytes(32)), "v1"),
                SecretReferenceKey.of(AesGcmCipher.randomBytes(32)),
                CLOCK);
        var active = activeConnectionWithSecret(binding, staged);
        var authority = SecretStoreTestFixtures.authority(wrongKeyStore, active, CLOCK);

        var invoked = new AtomicBoolean();
        assertThatThrownBy(() -> authority.consume(
                        SecretStoreTestFixtures.actor(),
                        SecretStoreTestFixtures.CONNECTION,
                        SecretStoreTestFixtures.connectionTest(),
                        new Deadline(NOW.plusSeconds(30)),
                        bytes -> invoked.set(true)))
                .isInstanceOf(SecretProviderException.class)
                .satisfies(ex -> assertNoLeakage(ex, directory, CANARY_PASSWORD));
        assertThat(invoked).isFalse();
    }

    @Test
    void resolvingCorruptedCiphertextFailsIntegrityVerificationWithoutInvokingTheConsumer() throws Exception {
        var store = newStore(directory);
        var binding = SecretStoreTestFixtures.binding(1);
        var staged = stageOnly(store, binding, SecretStoreTestFixtures.STAGE_OPERATION, CANARY_PASSWORD);

        Path activeDirectory = directory.resolve("active");
        Path envelopeFile = Files.list(activeDirectory).findFirst().orElseThrow();
        try (var raf = new RandomAccessFile(envelopeFile.toFile(), "rw")) {
            raf.seek(raf.length() - 5);
            raf.write(new byte[] {0x01, 0x02, 0x03});
        }

        var active = activeConnectionWithSecret(binding, staged);
        var authority = SecretStoreTestFixtures.authority(store, active, CLOCK);
        var invoked = new AtomicBoolean();

        assertThatThrownBy(() -> authority.consume(
                        SecretStoreTestFixtures.actor(),
                        SecretStoreTestFixtures.CONNECTION,
                        SecretStoreTestFixtures.connectionTest(),
                        new Deadline(NOW.plusSeconds(30)),
                        bytes -> invoked.set(true)))
                .isInstanceOf(SecretIntegrityException.class)
                .satisfies(ex -> assertNoLeakage(ex, directory, CANARY_PASSWORD));
        assertThat(invoked).isFalse();
    }

    /**
     * Shared S8 leakage guard: no failure-category exception in this
     * package's public surface may reveal the plaintext secret value, key
     * material, or a raw absolute filesystem path (including the opaque ID
     * as part of a path) via its message, {@code toString()}, or an attached
     * cause's message/{@code toString()}.
     */
    private static void assertNoLeakage(Throwable ex, Path storageDirectory, String plaintext) {
        String absolutePath = storageDirectory.toAbsolutePath().toString();
        assertThat(ex.getMessage()).isNotNull().doesNotContain(plaintext).doesNotContain(absolutePath);
        assertThat(ex.toString()).doesNotContain(plaintext).doesNotContain(absolutePath);
        Throwable cause = ex.getCause();
        if (cause != null) {
            String causeMessage = cause.getMessage();
            if (causeMessage != null) {
                assertThat(causeMessage).doesNotContain(plaintext).doesNotContain(absolutePath);
            }
            assertThat(cause.toString()).doesNotContain(plaintext).doesNotContain(absolutePath);
        }
    }

    @Test
    void revokeRemovesTheEnvelopeAndIsIdempotent() {
        var store = newStore(directory);
        var binding = SecretStoreTestFixtures.binding(1);
        var staged = stageOnly(store, binding, SecretStoreTestFixtures.STAGE_OPERATION, CANARY_PASSWORD);

        var deleting = deletingConnectionThatSupersededSecret(binding, staged);
        var revokeOperation = SecretStoreTestFixtures.revokeOperation(
                SecretStoreTestFixtures.REVOKE_OPERATION, binding, staged.value().reference());
        var authority = SecretStoreTestFixtures.authority(
                store, deleting, CLOCK, Optional.of(revokeOperation));

        authority.revokeForOperation(
                SecretStoreTestFixtures.WORKSPACE,
                SecretStoreTestFixtures.REVOKE_OPERATION,
                SecretStoreTestFixtures.CONNECTION,
                staged.value(),
                new Deadline(NOW.plusSeconds(30)));

        Path activeFile = directory.resolve("active").resolve(staged.value().reference().opaqueId() + ".secret");
        assertThat(Files.exists(activeFile)).isFalse();

        // Idempotent: revoking again (exact same operation/binding/reference) does not throw.
        authority.revokeForOperation(
                SecretStoreTestFixtures.WORKSPACE,
                SecretStoreTestFixtures.REVOKE_OPERATION,
                SecretStoreTestFixtures.CONNECTION,
                staged.value(),
                new Deadline(NOW.plusSeconds(30)));
    }

    @Test
    void stagingFailsClosedWhenStorageIsUnavailable() throws Exception {
        var store = newStore(directory);
        // Replace the writable active/ directory with a file, forcing every
        // write beneath it to fail with an IOException.
        Path activeDirectory = directory.resolve("active");
        deleteRecursively(activeDirectory);
        Files.createFile(activeDirectory);

        var binding = SecretStoreTestFixtures.binding(1);
        assertThatThrownBy(() -> stageOnly(store, binding, SecretStoreTestFixtures.STAGE_OPERATION, CANARY_PASSWORD))
                .isInstanceOf(SecretProviderException.class)
                .satisfies(ex -> assertNoLeakage(ex, directory, CANARY_PASSWORD));
    }

    @Test
    void cleanupOrphanedTemporaryFilesRemovesOnlyFilesOlderThanTheBoundedAge() throws Exception {
        var store = newStore(directory);
        Path tempDirectory = directory.resolve("tmp");
        Path stale = tempDirectory.resolve("staging-stale.secret");
        Path fresh = tempDirectory.resolve("staging-fresh.secret");
        Files.writeString(stale, "orphan");
        Files.writeString(fresh, "orphan");
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(7200)));

        int cleaned = store.cleanupOrphanedTemporaryFiles();

        assertThat(cleaned).isEqualTo(1);
        assertThat(Files.exists(stale)).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
    }

    @Test
    void cleanupRevokedTombstonesRemovesLeftoverFiles() throws Exception {
        var store = newStore(directory);
        Path revokedDirectory = directory.resolve("revoked");
        Files.writeString(revokedDirectory.resolve("leftover.secret"), "tombstone");

        int cleaned = store.cleanupRevokedTombstones();

        assertThat(cleaned).isEqualTo(1);
        try (var files = Files.list(revokedDirectory)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void storageDirectoriesAreOwnerOnlyWherePosixPermissionsAreSupported() throws Exception {
        newStore(directory);
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            var permissions = Files.getPosixFilePermissions(directory.resolve("active"));
            assertThat(permissions).containsExactlyInAnyOrder(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
        }
    }

    @Test
    void constructionFailsClosedWhenAPreExistingStorageSubdirectoryHasWeakerThanOwnerOnlyPermissions() throws Exception {
        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Path activeDirectory = directory.resolve("active");
        Files.createDirectories(activeDirectory);
        Files.setPosixFilePermissions(
                activeDirectory, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThatThrownBy(() -> newStore(directory))
                .isInstanceOf(SecretProviderException.class)
                .satisfies(ex -> assertNoLeakage(ex, directory, CANARY_PASSWORD));
    }

    private static LocalAesGcmSecretStore newStore(Path directory) {
        return new LocalAesGcmSecretStore(
                directory,
                KeyEncryptionKeyRing.of(Map.of("v1", AesGcmCipher.randomBytes(32)), "v1"),
                SecretReferenceKey.of(AesGcmCipher.randomBytes(32)),
                CLOCK);
    }

    private static com.knowimran.rivoquery.connection.application.secret.StagedSecretReference stage(
            com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority authority,
            com.knowimran.rivoquery.connection.application.secret.SecretBinding binding,
            String password) {
        try (var material = new DestructibleSecretMaterial(password.getBytes(StandardCharsets.UTF_8))) {
            return authority.stage(
                    SecretStoreTestFixtures.actor(),
                    SecretStoreTestFixtures.CONNECTION,
                    binding,
                    material,
                    SecretStoreTestFixtures.STAGE_OPERATION,
                    new Deadline(NOW.plusSeconds(30)));
        }
    }

    private static com.knowimran.rivoquery.connection.application.secret.StagedSecretReference stageOnly(
            LocalAesGcmSecretStore store,
            com.knowimran.rivoquery.connection.application.secret.SecretBinding binding,
            com.knowimran.rivoquery.connection.OperationId operationId,
            String password) {
        var connection = DatabaseConnection.reserve(
                        SecretStoreTestFixtures.WORKSPACE, SecretStoreTestFixtures.CONNECTION, DatabaseKind.POSTGRESQL)
                .reserveNextSecretVersion(new com.knowimran.rivoquery.connection.ConnectionVersion(1));
        DatabaseConnection atVersion = connection;
        for (long v = 2; v <= binding.version(); v++) {
            atVersion = atVersion.reserveNextSecretVersion(atVersion.version());
        }
        var authority = SecretStoreTestFixtures.authority(
                store, atVersion, CLOCK, Optional.of(SecretStoreTestFixtures.stageOperation(operationId, binding)));
        try (var material = new DestructibleSecretMaterial(password.getBytes(StandardCharsets.UTF_8))) {
            return authority.stage(
                    SecretStoreTestFixtures.actor(),
                    SecretStoreTestFixtures.CONNECTION,
                    binding,
                    material,
                    operationId,
                    new Deadline(NOW.plusSeconds(30)));
        }
    }

    private static DatabaseConnection activeConnectionWithSecret(
            com.knowimran.rivoquery.connection.application.secret.SecretBinding binding,
            com.knowimran.rivoquery.connection.application.secret.StagedSecretReference staged) {
        var connection = DatabaseConnection.reserve(
                        SecretStoreTestFixtures.WORKSPACE, SecretStoreTestFixtures.CONNECTION, DatabaseKind.POSTGRESQL)
                .reserveNextSecretVersion(new com.knowimran.rivoquery.connection.ConnectionVersion(1));
        var withSecret = connection.attachCurrentSecret(connection.version(), staged.value());
        return withSecret.transition(withSecret.version(), ConnectionState.ACTIVE);
    }

    private static DatabaseConnection deletingConnectionThatSupersededSecret(
            com.knowimran.rivoquery.connection.application.secret.SecretBinding binding,
            com.knowimran.rivoquery.connection.application.secret.StagedSecretReference staged) {
        var active = activeConnectionWithSecret(binding, staged);
        return active.transition(active.version(), ConnectionState.DELETING);
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path entry : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
