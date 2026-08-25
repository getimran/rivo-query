package com.knowimran.rivoquery.platform.secrets;

/**
 * The external key-encryption key configuration is missing, malformed, or
 * does not contain the key identifier an envelope requires. Fail-closed:
 * never falls back to an unwrapped or weaker mode.
 */
public final class SecretKeyUnavailableException extends SecretProviderException {
    public SecretKeyUnavailableException() {
        super(SecretFailureCategory.KEY_UNAVAILABLE, "secret store key encryption key is unavailable");
    }

    public SecretKeyUnavailableException(Throwable cause) {
        super(SecretFailureCategory.KEY_UNAVAILABLE, "secret store key encryption key is unavailable", cause);
    }
}
