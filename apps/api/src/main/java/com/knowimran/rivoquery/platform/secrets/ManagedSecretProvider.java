package com.knowimran.rivoquery.platform.secrets;

import com.knowimran.rivoquery.connection.application.Deadline;

/**
 * The minimal contract a genuine production-grade secret manager client
 * (a managed KMS, HashiCorp Vault, or an equivalent external secret broker)
 * must fulfill to back {@link ManagedSecretStore}. RivoQuery's own opaque
 * secret ID is the provider-side secret name/path, so no separate handle
 * type is needed; {@code authenticatedContext} carries only the non-secret
 * binding tuple (see {@code SecretBindingCodec}) so a provider that supports
 * encryption-context/AAD binding (e.g. AWS KMS encryption context, Vault
 * transit context) can enforce it independently of this adapter.
 *
 * <p>Implementations must be create-only for {@link #createVersion} (never
 * silently overwrite an existing opaque ID), must never return plaintext
 * except through {@link #readVersion}, and must fail closed - timeouts and
 * transient provider errors are the caller's responsibility to retry
 * idempotently, never to paper over with a weaker mode.
 */
public interface ManagedSecretProvider {
    void createVersion(String opaqueId, byte[] material, byte[] authenticatedContext, Deadline deadline);

    byte[] readVersion(String opaqueId, byte[] authenticatedContext, Deadline deadline);

    void deleteVersion(String opaqueId, Deadline deadline);
}
