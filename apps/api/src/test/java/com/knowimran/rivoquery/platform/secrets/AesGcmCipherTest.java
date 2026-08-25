package com.knowimran.rivoquery.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AesGcmCipherTest {
    private static final byte[] KEY = AesGcmCipher.randomBytes(AesGcmCipher.KEY_LENGTH_BYTES);

    @Test
    void roundTripsPlaintextWithMatchingKeyNonceAndAdditionalData() {
        byte[] nonce = AesGcmCipher.randomBytes(AesGcmCipher.NONCE_LENGTH_BYTES);
        byte[] plaintext = "canary-plaintext".getBytes(StandardCharsets.UTF_8);
        byte[] aad = "aad-context".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = AesGcmCipher.encrypt(KEY, nonce, plaintext, aad);
        byte[] decrypted = AesGcmCipher.decrypt(KEY, nonce, ciphertext, aad);

        assertThat(decrypted).isEqualTo(plaintext);
        assertThat(ciphertext).isNotEqualTo(plaintext);
    }

    @Test
    void twoEncryptionsOfTheSamePlaintextWithFreshNoncesProduceDifferentCiphertext() {
        byte[] plaintext = "same-plaintext".getBytes(StandardCharsets.UTF_8);
        byte[] aad = "aad".getBytes(StandardCharsets.UTF_8);

        byte[] first = AesGcmCipher.encrypt(KEY, AesGcmCipher.randomBytes(12), plaintext, aad);
        byte[] second = AesGcmCipher.encrypt(KEY, AesGcmCipher.randomBytes(12), plaintext, aad);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void decryptingWithAWrongKeyFailsIntegrityVerification() {
        byte[] nonce = AesGcmCipher.randomBytes(12);
        byte[] ciphertext = AesGcmCipher.encrypt(KEY, nonce, "unique-canary-plaintext-value".getBytes(StandardCharsets.UTF_8), "aad".getBytes(StandardCharsets.UTF_8));
        byte[] wrongKey = AesGcmCipher.randomBytes(AesGcmCipher.KEY_LENGTH_BYTES);

        assertThatThrownBy(() -> AesGcmCipher.decrypt(wrongKey, nonce, ciphertext, "aad".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SecretIntegrityException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, "unique-canary-plaintext-value",java.util.Base64.getEncoder().encodeToString(KEY),
                        java.util.Base64.getEncoder().encodeToString(wrongKey)));
    }

    @Test
    void decryptingTamperedCiphertextFailsIntegrityVerification() {
        byte[] nonce = AesGcmCipher.randomBytes(12);
        byte[] ciphertext = AesGcmCipher.encrypt(KEY, nonce, "unique-canary-plaintext-value".getBytes(StandardCharsets.UTF_8), "aad".getBytes(StandardCharsets.UTF_8));
        byte[] tampered = Arrays.copyOf(ciphertext, ciphertext.length);
        tampered[0] ^= (byte) 0xFF;

        assertThatThrownBy(() -> AesGcmCipher.decrypt(KEY, nonce, tampered, "aad".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SecretIntegrityException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, "unique-canary-plaintext-value",java.util.Base64.getEncoder().encodeToString(KEY)));
    }

    @Test
    void decryptingWithMismatchedAdditionalDataFailsIntegrityVerification() {
        byte[] nonce = AesGcmCipher.randomBytes(12);
        byte[] ciphertext = AesGcmCipher.encrypt(KEY, nonce, "unique-canary-plaintext-value".getBytes(StandardCharsets.UTF_8), "aad-a".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> AesGcmCipher.decrypt(KEY, nonce, ciphertext, "aad-b".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SecretIntegrityException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, "unique-canary-plaintext-value",java.util.Base64.getEncoder().encodeToString(KEY)));
    }
}
