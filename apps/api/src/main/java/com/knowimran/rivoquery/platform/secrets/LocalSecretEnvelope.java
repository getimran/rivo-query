package com.knowimran.rivoquery.platform.secrets;

import java.util.Objects;

/**
 * The on-disk local envelope. Contains only ciphertext, an authenticated
 * wrapped data key, nonces, and non-sensitive metadata - never plaintext,
 * never a raw data-encryption key, and never the key-encryption key itself.
 */
record LocalSecretEnvelope(
        int formatVersion,
        String opaqueId,
        String keyId,
        byte[] wrapNonce,
        byte[] wrappedDataKey,
        byte[] materialNonce,
        String bindingFingerprint,
        byte[] ciphertext) {

    LocalSecretEnvelope {
        Objects.requireNonNull(opaqueId, "opaqueId");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(wrapNonce, "wrapNonce");
        Objects.requireNonNull(wrappedDataKey, "wrappedDataKey");
        Objects.requireNonNull(materialNonce, "materialNonce");
        Objects.requireNonNull(bindingFingerprint, "bindingFingerprint");
        Objects.requireNonNull(ciphertext, "ciphertext");
    }

    @Override
    public String toString() {
        return "LocalSecretEnvelope[REDACTED]";
    }
}
