package com.knowimran.rivoquery.platform.secrets;

import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretAccessGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretMutationGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretRevocationGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretLease;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import com.knowimran.rivoquery.connection.application.secret.SecretStore;
import com.knowimran.rivoquery.connection.application.secret.StagedSecretReference;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Development-only external-key AES-256-GCM {@link SecretStore} adapter. Not
 * a production recommendation (ADR-0008). Persists ciphertext-only envelopes
 * under a configured directory outside the repository and outside the
 * control-plane PostgreSQL data directory; the control database never
 * receives ciphertext, nonces, or wrapped keys through this class. The
 * key-encryption key and reference key are supplied only through
 * {@link KeyEncryptionKeyRing}/{@link SecretReferenceKey}, both sourced from
 * runtime configuration external to this class.
 *
 * <p>This is the sole {@code SecretStore} entry point for the local adapter:
 * every method validates the caller-supplied grant against the binding or
 * reference before doing anything else, then delegates the storage mechanics
 * to {@link LocalSecretStorage}, which knows nothing about grants. It may
 * reference the sealed {@code SecretAccessGrant}/{@code SecretMutationGrant}/
 * {@code SecretRevocationGrant} interfaces only to verify a caller-supplied
 * binding; it has no way to construct one (construction is private to
 * {@code SecretCapabilityAuthority}).
 */
public final class LocalAesGcmSecretStore implements SecretStore {
    private static final String PROVIDER_NAMESPACE = "local-aes-gcm";

    private final LocalSecretStorage storage;
    private final Clock clock;

    public LocalAesGcmSecretStore(
            Path storageDirectory, KeyEncryptionKeyRing keyRing, SecretReferenceKey referenceKey, Clock clock) {
        this.storage = new LocalSecretStorage(storageDirectory, keyRing, referenceKey);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public StagedSecretReference stage(
            SecretBinding binding, DestructibleSecretMaterial material, SecretMutationGrant grant, Deadline deadline) {
        DeadlineChecks.requireUsable(deadline, clock);
        DeadlineChecks.requireUsable(grant.deadline(), clock);
        SecretGrantValidation.requireStageGrantMatches(binding, grant);
        return storage.stage(PROVIDER_NAMESPACE, binding, material, grant.authorityId());
    }

    @Override
    public SecretLease resolve(SecretReference reference, SecretAccessGrant grant, Deadline deadline) {
        DeadlineChecks.requireUsable(deadline, clock);
        DeadlineChecks.requireUsable(grant.deadline(), clock);
        SecretGrantValidation.requireAccessGrantMatches(reference, grant);
        byte[] plaintext = storage.resolvePlaintext(reference, grant.binding());
        Instant effectiveExpiry = DeadlineChecks.earlier(deadline.expiresAt(), grant.deadline().expiresAt());
        return new BoundedSecretLease(plaintext, new Deadline(effectiveExpiry), clock);
    }

    @Override
    public void revoke(SecretReference reference, SecretRevocationGrant grant, Deadline deadline) {
        DeadlineChecks.requireUsable(deadline, clock);
        DeadlineChecks.requireUsable(grant.deadline(), clock);
        SecretGrantValidation.requireRevocationGrantMatches(reference, grant);
        storage.revoke(reference);
    }

    /**
     * Deletes create-only temporary files older than a bounded age that were
     * never linked into active storage because the process crashed between
     * writing and linking. Safe to call repeatedly; not yet scheduled by any
     * framework component (deferred to the operational wiring that enables
     * this adapter).
     */
    public int cleanupOrphanedTemporaryFiles() {
        return storage.cleanupOrphanedTemporaryFiles();
    }

    /**
     * Deletes tombstoned envelopes that were already moved out of active
     * storage but not yet physically deleted, for example because the
     * process crashed between the tombstone move and the delete in
     * {@link #revoke}. Safe to call repeatedly; not yet scheduled by any
     * framework component.
     */
    public int cleanupRevokedTombstones() {
        return storage.cleanupRevokedTombstones();
    }
}
