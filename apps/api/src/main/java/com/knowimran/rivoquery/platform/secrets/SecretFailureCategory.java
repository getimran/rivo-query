package com.knowimran.rivoquery.platform.secrets;

/**
 * Safe, non-secret-shaped classification of a {@code SecretStore} adapter
 * failure. Used only for internal exception typing and canary telemetry;
 * never carries key material, ciphertext, plaintext, or file paths.
 */
public enum SecretFailureCategory {
    KEY_UNAVAILABLE,
    INTEGRITY_VIOLATION,
    STORAGE_UNAVAILABLE,
    STAGING_CONFLICT,
    GRANT_MISMATCH,
    LEASE_UNUSABLE,
    PROVIDER_NOT_CONFIGURED
}
