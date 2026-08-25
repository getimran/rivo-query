package com.knowimran.rivoquery.platform.secrets;

import com.knowimran.rivoquery.connection.application.Deadline;

/**
 * Documented, deliberate fail-closed placeholder for {@link ManagedSecretProvider}.
 *
 * <p><strong>This is not a production secret manager implementation.</strong>
 * It exists so that {@link ManagedSecretStore} - and therefore the
 * production {@code SecretStore} shape - can be exercised in tests and wired
 * into the application without a real KMS/Vault/secret-broker dependency,
 * while making it structurally impossible for that absence to be mistaken
 * for a working (or weaker) secret store: every method call fails closed
 * with {@link SecretManagerNotConfiguredException} rather than falling back
 * to local storage, an in-memory map, plaintext, or any other substitute.
 *
 * <p>Before enabling production secret storage, replace this class with a
 * real implementation backed by the operator's chosen provider (for example
 * AWS/GCP/Azure KMS-wrapped secret storage, HashiCorp Vault's transit or
 * KV-v2 engine, or an equivalent managed secret broker). Do not extend or
 * "complete" this class by quietly delegating to {@link LocalAesGcmSecretStore}'s
 * envelope logic - that would be exactly the fake-production adapter this
 * design forbids.
 */
public final class UnimplementedManagedSecretProvider implements ManagedSecretProvider {
    @Override
    public void createVersion(String opaqueId, byte[] material, byte[] authenticatedContext, Deadline deadline) {
        throw new SecretManagerNotConfiguredException();
    }

    @Override
    public byte[] readVersion(String opaqueId, byte[] authenticatedContext, Deadline deadline) {
        throw new SecretManagerNotConfiguredException();
    }

    @Override
    public void deleteVersion(String opaqueId, Deadline deadline) {
        throw new SecretManagerNotConfiguredException();
    }
}
