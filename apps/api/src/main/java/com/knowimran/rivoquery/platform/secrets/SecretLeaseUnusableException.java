package com.knowimran.rivoquery.platform.secrets;

/**
 * A {@code SecretLease} was consumed after its bounded lifetime expired,
 * from a thread other than the one that obtained it, or after it was already
 * consumed/closed. Enforced at use time by the lease itself, independent of
 * whether the caller already checked its own deadline.
 */
public final class SecretLeaseUnusableException extends SecretProviderException {
    public SecretLeaseUnusableException() {
        super(SecretFailureCategory.LEASE_UNUSABLE, "secret lease is no longer usable");
    }
}
