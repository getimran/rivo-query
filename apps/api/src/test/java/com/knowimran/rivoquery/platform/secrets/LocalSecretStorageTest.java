package com.knowimran.rivoquery.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Direct coverage of {@link LocalSecretStorage} (package-private, exercised
 * indirectly by {@link LocalAesGcmSecretStoreTest}) for the specific
 * "does not reveal whether an opaque ID exists" guarantee documented on
 * {@link LocalSecretStorage#readEnvelope} and
 * {@code docs/design/secret-store-capabilities.md} - the guarantee only
 * holds if a caller cannot distinguish "this opaque ID was never staged"
 * from "this opaque ID exists but is bound to something else" by exception
 * type, message, or {@code toString()} content.
 */
class LocalSecretStorageTest {
    private static final String CANARY_PASSWORD = "unique-canary-password-value";

    @TempDir
    Path directory;

    @Test
    void resolvingAnOpaqueIdThatNeverExistedAndResolvingAPresentButWrongBindingEnvelopeThrowTheExactSameExceptionType() {
        var storage = newStorage(directory);
        var binding = SecretStoreTestFixtures.binding(1);
        var staged = stage(storage, binding, CANARY_PASSWORD);
        var realReference = staged.value().reference();

        // Case 1: opaque ID that was never staged at all.
        var neverExistedReference = new SecretReference(
                realReference.providerNamespace(),
                "never-existed-" + "a".repeat(24),
                realReference.version(),
                realReference.bindingFingerprint());

        // Case 2: the opaque ID exists (it is exactly the envelope staged
        // above) but the reference presented for it carries a binding
        // fingerprint that does not match what is on disk.
        var otherBinding = SecretStoreTestFixtures.binding(2);
        var wrongBindingReference = new SecretReference(
                realReference.providerNamespace(),
                realReference.opaqueId(),
                realReference.version(),
                SecretBindingCodec.fingerprint(otherBinding));

        Exception missing = catchException(() -> storage.resolvePlaintext(neverExistedReference, binding));
        Exception wrongBinding = catchException(() -> storage.resolvePlaintext(wrongBindingReference, binding));

        assertThat(missing).isInstanceOf(SecretIntegrityException.class);
        assertThat(wrongBinding).isInstanceOf(SecretIntegrityException.class);
        assertThat(missing.getClass()).isEqualTo(wrongBinding.getClass());
        assertThat(missing.getMessage()).isEqualTo(wrongBinding.getMessage());
        assertThat(missing.getCause()).isNull();
        assertThat(wrongBinding.getCause()).isNull();

        for (Exception ex : new Exception[] {missing, wrongBinding}) {
            assertThat(ex.getMessage()).doesNotContain(CANARY_PASSWORD);
            assertThat(ex.getMessage()).doesNotContain(realReference.opaqueId());
            assertThat(ex.getMessage()).doesNotContain(directory.toAbsolutePath().toString());
            assertThat(ex.toString()).doesNotContain(CANARY_PASSWORD);
            assertThat(ex.toString()).doesNotContain(realReference.opaqueId());
            assertThat(ex.toString()).doesNotContain(directory.toAbsolutePath().toString());
        }
    }

    @Test
    void cleanupFailuresRedactTheUnderlyingIoExceptionSoTheDirectoryPathNeverSurfaces() throws Exception {
        var storage = newStorage(directory);
        java.nio.file.Path tempDirectory = directory.resolve("tmp");
        deleteRecursively(tempDirectory);
        java.nio.file.Files.createFile(tempDirectory);

        assertThatThrownBy(storage::cleanupOrphanedTemporaryFiles)
                .isInstanceOf(SecretStorageUnavailableException.class)
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).doesNotContain(directory.toAbsolutePath().toString());
                    assertThat(ex.toString()).doesNotContain(directory.toAbsolutePath().toString());
                    Throwable cause = ex.getCause();
                    assertThat(cause).isNotNull();
                    assertThat(cause.getMessage()).doesNotContain(directory.toAbsolutePath().toString());
                    assertThat(cause.toString()).doesNotContain(directory.toAbsolutePath().toString());
                });
    }

    private static LocalSecretStorage newStorage(Path directory) {
        return new LocalSecretStorage(
                directory,
                KeyEncryptionKeyRing.of(Map.of("v1", AesGcmCipher.randomBytes(32)), "v1"),
                SecretReferenceKey.of(AesGcmCipher.randomBytes(32)));
    }

    private static com.knowimran.rivoquery.connection.application.secret.StagedSecretReference stage(
            LocalSecretStorage storage, SecretBinding binding, String password) {
        try (var material = new DestructibleSecretMaterial(password.getBytes(StandardCharsets.UTF_8))) {
            return storage.stage("local-aes-gcm", binding, material, SecretStoreTestFixtures.STAGE_OPERATION.value());
        }
    }

    private static Exception catchException(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            return e;
        }
        throw new AssertionError("expected an exception but none was thrown");
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!java.nio.file.Files.exists(path)) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(path)) {
            for (Path entry : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(entry);
            }
        }
    }
}
