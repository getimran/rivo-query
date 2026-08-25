package com.knowimran.rivoquery.platform.secrets;

/**
 * No production secret manager integration is wired. Raised by every
 * {@link UnimplementedManagedSecretProvider} method. This is a deliberate,
 * documented, fail-closed placeholder - it is not a weaker or fallback
 * implementation of secret storage and must never be reinterpreted as one.
 * Selecting it is equivalent to refusing to store or resolve any production
 * secret until a genuine KMS/Vault/secret-broker-backed
 * {@link ManagedSecretProvider} implementation is substituted.
 */
public final class SecretManagerNotConfiguredException extends SecretProviderException {
    public SecretManagerNotConfiguredException() {
        super(
                SecretFailureCategory.PROVIDER_NOT_CONFIGURED,
                "no production secret manager provider is configured; "
                        + "the unimplemented stub adapter refuses every operation by design");
    }
}
