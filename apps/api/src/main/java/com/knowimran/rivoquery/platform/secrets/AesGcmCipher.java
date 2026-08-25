package com.knowimran.rivoquery.platform.secrets;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal AES-256-GCM helper shared by the key-encryption-key ring and the
 * local envelope adapter. Every nonce is freshly random; every operation is
 * authenticated with caller-supplied additional data. Never logs or embeds
 * key/plaintext material in an exception message.
 */
final class AesGcmCipher {
    static final int KEY_LENGTH_BYTES = 32;
    static final int NONCE_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcmCipher() {
    }

    static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static byte[] encrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] additionalData) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(additionalData);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new SecretKeyUnavailableException(e);
        }
    }

    static byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] additionalData) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(additionalData);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new SecretIntegrityException(e);
        }
    }
}
