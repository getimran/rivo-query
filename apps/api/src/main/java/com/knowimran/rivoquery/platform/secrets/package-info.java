/**
 * SecretStore adapters (ADR-0008, {@code docs/design/secret-store-capabilities.md}).
 *
 * <p>Every class here implements or supports {@code SecretStore}
 * ({@code connection.application.secret}): a development-only external-key
 * AES-256-GCM local adapter ({@link com.knowimran.rivoquery.platform.secrets.LocalAesGcmSecretStore})
 * and a production port with a documented, deliberately unimplemented stub
 * ({@link com.knowimran.rivoquery.platform.secrets.ManagedSecretStore},
 * {@link com.knowimran.rivoquery.platform.secrets.ManagedSecretProvider},
 * {@link com.knowimran.rivoquery.platform.secrets.UnimplementedManagedSecretProvider}).
 *
 * <p>This package may reference the sealed {@code SecretAccessGrant}/
 * {@code SecretMutationGrant}/{@code SecretRevocationGrant} interfaces only
 * to verify a caller-supplied binding while implementing {@code SecretStore};
 * it has no way to construct one (construction is private to
 * {@code SecretCapabilityAuthority}). It never depends on
 * {@code platform.persistence}, {@code connection.connector},
 * {@code connection.pool}, a worker package, or a transport adapter package,
 * and no class outside this package may implement {@code SecretStore}.
 */
package com.knowimran.rivoquery.platform.secrets;
