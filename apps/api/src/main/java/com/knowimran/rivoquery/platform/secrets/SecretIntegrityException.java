package com.knowimran.rivoquery.platform.secrets;

/**
 * Ciphertext, an envelope header, or a wrapped data key failed authenticated
 * decryption or otherwise does not match its expected binding. Covers wrong
 * key, tampered/corrupt ciphertext, and binding-mismatch detection. Never
 * reveals whether the underlying opaque identifier exists.
 */
public final class SecretIntegrityException extends SecretProviderException {
    public SecretIntegrityException() {
        super(SecretFailureCategory.INTEGRITY_VIOLATION, "secret material failed integrity verification");
    }

    public SecretIntegrityException(Throwable cause) {
        super(SecretFailureCategory.INTEGRITY_VIOLATION, "secret material failed integrity verification", cause);
    }
}
