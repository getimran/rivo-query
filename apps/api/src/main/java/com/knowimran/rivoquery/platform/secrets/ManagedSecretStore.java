package com.knowimran.rivoquery.platform.secrets;

import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.secret.BoundSecretReference;
import com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretAccessGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretMutationGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretRevocationGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretLease;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import com.knowimran.rivoquery.connection.application.secret.SecretStore;
import com.knowimran.rivoquery.connection.application.secret.StagedSecretReference;
import java.time.Clock;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Production {@link SecretStore} port, delegating storage to an injected
 * {@link ManagedSecretProvider} (a genuine KMS/Vault/secret-broker client in
 * a real deployment; {@link UnimplementedManagedSecretProvider} until one is
 * wired). Performs exactly the same grant/binding validation as
 * {@link LocalAesGcmSecretStore} via the shared {@link SecretGrantValidation}
 * helper and the same deterministic opaque-ID derivation via
 * {@link SecretReferenceKey}, so the two adapters are provably equivalent at
 * the {@code SecretStore} boundary regardless of which one is wired.
 */
public final class ManagedSecretStore implements SecretStore {
    private static final int FORMAT_VERSION = 1;
    private static final Pattern NAMESPACE = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

    private final ManagedSecretProvider provider;
    private final SecretReferenceKey referenceKey;
    private final String providerNamespace;
    private final Clock clock;

    public ManagedSecretStore(
            ManagedSecretProvider provider, SecretReferenceKey referenceKey, String providerNamespace, Clock clock) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.referenceKey = Objects.requireNonNull(referenceKey, "referenceKey");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(providerNamespace, "providerNamespace");
        if (!NAMESPACE.matcher(providerNamespace).matches()) {
            throw new IllegalArgumentException("provider namespace is invalid");
        }
        this.providerNamespace = providerNamespace;
    }

    @Override
    public StagedSecretReference stage(
            SecretBinding binding, DestructibleSecretMaterial material, SecretMutationGrant grant, Deadline deadline) {
        DeadlineChecks.requireUsable(deadline, clock);
        DeadlineChecks.requireUsable(grant.deadline(), clock);
        SecretGrantValidation.requireStageGrantMatches(binding, grant);
        String opaqueId = referenceKey.opaqueId(binding, grant.authorityId());
        byte[] context = SecretBindingCodec.additionalData(FORMAT_VERSION, binding);
        Objects.requireNonNull(material, "material")
                .consume(bytes -> {
                    provider.createVersion(opaqueId, bytes, context, deadline);
                    return null;
                });
        String bindingFingerprint = SecretBindingCodec.fingerprint(binding);
        var reference = new SecretReference(providerNamespace, opaqueId, binding.version(), bindingFingerprint);
        return new StagedSecretReference(new BoundSecretReference(binding, reference));
    }

    @Override
    public SecretLease resolve(SecretReference reference, SecretAccessGrant grant, Deadline deadline) {
        DeadlineChecks.requireUsable(deadline, clock);
        DeadlineChecks.requireUsable(grant.deadline(), clock);
        SecretGrantValidation.requireAccessGrantMatches(reference, grant);
        byte[] context = SecretBindingCodec.additionalData(FORMAT_VERSION, grant.binding());
        byte[] plaintext = provider.readVersion(reference.opaqueId(), context, deadline);
        var effectiveDeadline = new Deadline(
                deadline.expiresAt().isBefore(grant.deadline().expiresAt())
                        ? deadline.expiresAt()
                        : grant.deadline().expiresAt());
        return new BoundedSecretLease(plaintext, effectiveDeadline, clock);
    }

    @Override
    public void revoke(SecretReference reference, SecretRevocationGrant grant, Deadline deadline) {
        DeadlineChecks.requireUsable(deadline, clock);
        DeadlineChecks.requireUsable(grant.deadline(), clock);
        SecretGrantValidation.requireRevocationGrantMatches(reference, grant);
        provider.deleteVersion(reference.opaqueId(), deadline);
    }
}
