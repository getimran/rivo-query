package com.knowimran.rivoquery.platform.destination.pool;

import com.knowimran.rivoquery.connection.application.destination.DestinationAssessment;
import com.knowimran.rivoquery.connection.application.destination.DestinationPolicy;
import com.knowimran.rivoquery.connection.application.destination.DestinationRequest;
import com.knowimran.rivoquery.connection.pool.PoolDrainReason;
import com.knowimran.rivoquery.connection.pool.PoolKey;
import com.knowimran.rivoquery.connection.pool.TargetPoolManager;
import com.knowimran.rivoquery.platform.destination.DestinationDenialReason;
import com.knowimran.rivoquery.platform.destination.DestinationValidationException;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link PoolDestinationRevalidator}: re-resolves through the same {@link
 * DestinationPolicy} used for physical-connection validation, so revalidation always reflects the
 * current DNS answer and current destination policy, never a cached decision.
 */
public final class DestinationPolicyPoolRevalidator implements PoolDestinationRevalidator {
    private final DestinationPolicy destinationPolicy;
    private final TargetPoolManager poolManager;
    private final Clock clock;

    /**
     * Once a {@link PoolKey} has been drained for cause (a stale/changed binding, or a failed
     * re-resolution), that exact key must never be revalidated back to "fresh" again -- not even
     * if a later re-resolution happens to match the previously-bound fingerprint again (e.g. DNS
     * flapping back to its original answer). {@link PoolDestinationBinding} is an immutable record
     * with no quarantine marker of its own, so this instance tracks it. Full ownership of pool
     * lifecycle state (including this) more properly belongs to {@code TargetPoolManager}, but
     * that has no real implementation yet (tracked separately); until it does, this revalidator is
     * the only component that observes a quarantine-worthy event, so it enforces the invariant
     * here. Entries are never removed: recovering requires full re-registration under a new {@link
     * PoolKey} (e.g. a bumped connection or destination-policy version), which is a distinct key
     * and is therefore unaffected by this set.
     */
    private final Set<PoolKey> quarantinedKeys = ConcurrentHashMap.newKeySet();

    public DestinationPolicyPoolRevalidator(DestinationPolicy destinationPolicy, TargetPoolManager poolManager, Clock clock) {
        this.destinationPolicy = Objects.requireNonNull(destinationPolicy, "destinationPolicy");
        this.poolManager = Objects.requireNonNull(poolManager, "poolManager");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public DestinationAssessment ensureFreshForBorrow(PoolDestinationBinding binding, DestinationRequest request) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(request, "request");

        if (quarantinedKeys.contains(binding.key())) {
            // Already drained for cause: refuse outright, without spending a DNS lookup on a key
            // that can never be resurrected. See the field Javadoc.
            throw new DestinationValidationException(
                    DestinationDenialReason.BINDING_STALE,
                    "pooled destination binding was previously quarantined and cannot be revalidated;"
                            + " full re-registration is required");
        }

        DestinationAssessment current = binding.assessment();

        if (clock.instant().isBefore(current.expiresAt())) {
            return current; // still fresh: no new DNS traffic, no pool disruption
        }

        // Expired: the caller must not lend against this binding until it is re-validated. No
        // separate "pause" call exists on TargetPoolManager; synchronously withholding a usable
        // assessment from the caller until this method returns is the pause.
        DestinationAssessment fresh;
        try {
            fresh = destinationPolicy.assess(request);
        } catch (RuntimeException resolutionFailed) {
            quarantine(binding.key(), request);
            throw resolutionFailed;
        }

        boolean unchangedAndAllowed = fresh.addressSetFingerprint().equals(current.addressSetFingerprint())
                && fresh.policyVersion().equals(current.policyVersion());
        if (unchangedAndAllowed) {
            return fresh; // renewed: identical vetted set, still under the currently bound policy
        }

        quarantine(binding.key(), request);
        throw new DestinationValidationException(
                DestinationDenialReason.BINDING_STALE,
                "pooled destination binding changed or expired and was quarantined");
    }

    private void quarantine(PoolKey key, DestinationRequest request) {
        quarantinedKeys.add(key);
        poolManager.stopNewBorrowsAndDrain(key, PoolDrainReason.POLICY_CHANGED, request.deadline());
    }
}
