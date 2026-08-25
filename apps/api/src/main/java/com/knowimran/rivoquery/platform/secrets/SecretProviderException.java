package com.knowimran.rivoquery.platform.secrets;

import java.util.Objects;

/**
 * Base type for every failure a {@code SecretStore} adapter in this package
 * can raise. Messages are fixed and safe by construction (never interpolate
 * key material, plaintext, ciphertext, or provider-supplied detail); a cause
 * may be attached for operator diagnosis but is never required and never
 * itself required to be safe to print (callers must not surface raw cause
 * detail to a client - that mapping happens above this package).
 */
public abstract sealed class SecretProviderException extends RuntimeException
        permits SecretKeyUnavailableException, SecretIntegrityException, SecretStorageUnavailableException,
                SecretStagingConflictException, SecretGrantMismatchException, SecretLeaseUnusableException,
                SecretManagerNotConfiguredException {

    private final SecretFailureCategory category;

    SecretProviderException(SecretFailureCategory category, String safeMessage) {
        super(safeMessage);
        this.category = Objects.requireNonNull(category, "category");
    }

    SecretProviderException(SecretFailureCategory category, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.category = Objects.requireNonNull(category, "category");
    }

    public final SecretFailureCategory category() {
        return category;
    }
}
