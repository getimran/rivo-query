package com.knowimran.rivoquery.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KeyEncryptionKeyRingTest {
    private static final byte[] KEY_V1 = AesGcmCipher.randomBytes(32);
    private static final byte[] KEY_V2 = AesGcmCipher.randomBytes(32);

    @Test
    void wrapAndUnwrapRoundTripsTheDataEncryptionKey() {
        var ring = KeyEncryptionKeyRing.of(Map.of("v1", KEY_V1), "v1");
        byte[] dek = AesGcmCipher.randomBytes(32);
        byte[] aad = "context".getBytes(StandardCharsets.UTF_8);

        var wrapped = ring.wrap(dek, aad);
        byte[] unwrapped = ring.unwrap(wrapped.keyId(), wrapped.nonce(), wrapped.ciphertext(), aad);

        assertThat(unwrapped).isEqualTo(dek);
        assertThat(wrapped.keyId()).isEqualTo("v1");
    }

    @Test
    void unwrapWithUnknownKeyIdFailsClosed() {
        var ring = KeyEncryptionKeyRing.of(Map.of("v1", KEY_V1), "v1");

        assertThatThrownBy(() -> ring.unwrap("does-not-exist", new byte[12], new byte[32], new byte[0]))
                .isInstanceOf(SecretKeyUnavailableException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, Base64.getEncoder().encodeToString(KEY_V1)));
    }

    @Test
    void unwrapAfterRotationStillResolvesOldCiphertextWhileOldKeyIsRetained() {
        var beforeRotation = KeyEncryptionKeyRing.of(Map.of("v1", KEY_V1), "v1");
        byte[] dek = AesGcmCipher.randomBytes(32);
        var wrappedUnderV1 = beforeRotation.wrap(dek, "ctx".getBytes(StandardCharsets.UTF_8));

        var afterRotation = KeyEncryptionKeyRing.of(Map.of("v1", KEY_V1, "v2", KEY_V2), "v2");
        byte[] unwrapped = afterRotation.unwrap(
                wrappedUnderV1.keyId(), wrappedUnderV1.nonce(), wrappedUnderV1.ciphertext(),
                "ctx".getBytes(StandardCharsets.UTF_8));

        assertThat(unwrapped).isEqualTo(dek);
        assertThat(afterRotation.wrap(dek, "ctx".getBytes(StandardCharsets.UTF_8)).keyId()).isEqualTo("v2");
    }

    @Test
    void ofRejectsAKeyThatIsNotExactly256Bits() {
        assertThatThrownBy(() -> KeyEncryptionKeyRing.of(Map.of("v1", new byte[16]), "v1"))
                .isInstanceOf(SecretKeyUnavailableException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(ex, Base64.getEncoder().encodeToString(new byte[16])));
    }

    @Test
    void ofRejectsACurrentKeyIdThatIsNotInTheMap() {
        assertThatThrownBy(() -> KeyEncryptionKeyRing.of(Map.of("v1", KEY_V1), "missing"))
                .isInstanceOf(SecretKeyUnavailableException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, Base64.getEncoder().encodeToString(KEY_V1)));
    }

    @Test
    void ofRejectsAnEmptyKeyMap() {
        assertThatThrownBy(() -> KeyEncryptionKeyRing.of(Map.of(), "v1"))
                .isInstanceOf(SecretKeyUnavailableException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, Base64.getEncoder().encodeToString(KEY_V1)));
    }

    @Test
    void fromEnvironmentParsesACommaSeparatedRotationSetAndHonorsTheCurrentKeyId() {
        String encodedV1 = Base64.getEncoder().encodeToString(KEY_V1);
        String encodedV2 = Base64.getEncoder().encodeToString(KEY_V2);
        Map<String, String> env = Map.of(
                KeyEncryptionKeyRing.KEYS_ENV_VAR, "v1:" + encodedV1 + ",v2:" + encodedV2,
                KeyEncryptionKeyRing.CURRENT_KEY_ID_ENV_VAR, "v2");

        var ring = KeyEncryptionKeyRing.fromEnvironment(env::get);

        byte[] dek = AesGcmCipher.randomBytes(32);
        var wrapped = ring.wrap(dek, new byte[0]);
        assertThat(wrapped.keyId()).isEqualTo("v2");
        assertThat(ring.unwrap(wrapped.keyId(), wrapped.nonce(), wrapped.ciphertext(), new byte[0])).isEqualTo(dek);

        // "v1" was also parsed into the rotation set (not just the current
        // key), proving multi-key configuration strings are honored: a
        // separately built ring using it directly can unwrap ciphertext
        // this ring's "v1" entry would also be able to unwrap.
        var v1OnlyRing = KeyEncryptionKeyRing.of(Map.of("v1", KEY_V1), "v1");
        var wrappedUnderV1 = v1OnlyRing.wrap(dek, new byte[0]);
        assertThat(ring.unwrap("v1", wrappedUnderV1.nonce(), wrappedUnderV1.ciphertext(), new byte[0]))
                .isEqualTo(dek);
    }

    @Test
    void fromEnvironmentFailsClosedWhenKeysAreMissing() {
        assertThatThrownBy(() -> KeyEncryptionKeyRing.fromEnvironment(name -> null))
                .isInstanceOf(SecretKeyUnavailableException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, Base64.getEncoder().encodeToString(KEY_V1)));
    }

    @Test
    void fromEnvironmentFailsClosedWhenCurrentKeyIdIsBlank() {
        Map<String, String> env = Map.of(
                KeyEncryptionKeyRing.KEYS_ENV_VAR, "v1:" + Base64.getEncoder().encodeToString(KEY_V1),
                KeyEncryptionKeyRing.CURRENT_KEY_ID_ENV_VAR, "  ");

        assertThatThrownBy(() -> KeyEncryptionKeyRing.fromEnvironment(env::get))
                .isInstanceOf(SecretKeyUnavailableException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, Base64.getEncoder().encodeToString(KEY_V1)));
    }

    @Test
    void fromEnvironmentFailsClosedOnMalformedEntries() {
        Map<String, String> env = Map.of(
                KeyEncryptionKeyRing.KEYS_ENV_VAR, "not-a-valid-entry",
                KeyEncryptionKeyRing.CURRENT_KEY_ID_ENV_VAR, "v1");

        assertThatThrownBy(() -> KeyEncryptionKeyRing.fromEnvironment(env::get))
                .isInstanceOf(SecretKeyUnavailableException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(ex, "not-a-valid-entry"));
    }

    @Test
    void toStringAndWrappedDataKeyToStringAreRedacted() {
        var ring = KeyEncryptionKeyRing.of(Map.of("v1", KEY_V1), "v1");
        assertThat(ring.toString()).isEqualTo("KeyEncryptionKeyRing[REDACTED]");
        var wrapped = ring.wrap(AesGcmCipher.randomBytes(32), new byte[0]);
        assertThat(wrapped.toString()).isEqualTo("WrappedDataKey[REDACTED]");
    }
}
