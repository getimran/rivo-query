package com.knowimran.rivoquery.platform.secrets;

/**
 * The supplied grant, reference, or binding do not agree exactly. Releases
 * no plaintext. Public error detail never reveals whether a foreign secret
 * ID exists (this exception is raised identically for "not found" and for
 * "found but bound differently").
 */
public final class SecretGrantMismatchException extends SecretProviderException {
    public SecretGrantMismatchException() {
        super(SecretFailureCategory.GRANT_MISMATCH, "secret grant does not match the requested binding");
    }
}
