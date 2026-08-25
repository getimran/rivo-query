package com.knowimran.rivoquery.platform.secrets;

/**
 * A create-only {@code stage} call reused an exact mutation operation and
 * binding that is already durably staged with different material. A true
 * replay (same operation, binding, and material) is idempotent and returns
 * the existing reference instead of raising this exception.
 */
public final class SecretStagingConflictException extends SecretProviderException {
    public SecretStagingConflictException() {
        super(SecretFailureCategory.STAGING_CONFLICT, "secret staging operation conflicts with an existing version");
    }
}
