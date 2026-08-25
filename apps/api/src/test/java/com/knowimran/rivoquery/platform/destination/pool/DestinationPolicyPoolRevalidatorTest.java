package com.knowimran.rivoquery.platform.destination.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.destination.AddressFamily;
import com.knowimran.rivoquery.connection.application.destination.ConnectionPurpose;
import com.knowimran.rivoquery.connection.application.destination.DestinationAssessment;
import com.knowimran.rivoquery.connection.application.destination.DestinationPolicy;
import com.knowimran.rivoquery.connection.application.destination.DestinationRequest;
import com.knowimran.rivoquery.connection.application.destination.StructuredEndpoint;
import com.knowimran.rivoquery.connection.application.destination.VettedAddress;
import com.knowimran.rivoquery.connection.pool.PoolDrainReason;
import com.knowimran.rivoquery.connection.pool.PoolKey;
import com.knowimran.rivoquery.connection.pool.TargetPoolManager;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.platform.destination.DestinationDenialReason;
import com.knowimran.rivoquery.platform.destination.DestinationValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DestinationPolicyPoolRevalidatorTest {
    private static final String ULID = "01J6MZ4H9ST5N7YQ2C8W3V0KFX";
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_" + ULID);
    private static final ConnectionId CONNECTION = new ConnectionId("con_" + ULID);
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void anUnexpiredBindingIsReusedWithoutReResolutionOrDraining() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var policy = new RecordingPolicy(assessment(NOW.plusSeconds(60), "1.2.3.4"));
        var poolManager = new RecordingPoolManager();
        var revalidator = new DestinationPolicyPoolRevalidator(policy, poolManager, clock);
        var binding = new PoolDestinationBinding(poolKey("v1"), assessment(NOW.plusSeconds(30), "1.2.3.4"));

        DestinationAssessment result = revalidator.ensureFreshForBorrow(binding, request());

        assertThat(result).isEqualTo(binding.assessment());
        assertThat(policy.callCount).isZero();
        assertThat(poolManager.drainCalls).isEmpty();
    }

    @Test
    void anExpiredBindingWithUnchangedDnsIsRenewedWithoutDraining() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var freshSameAddress = assessment(NOW.plusSeconds(60), "1.2.3.4");
        var policy = new RecordingPolicy(freshSameAddress);
        var poolManager = new RecordingPoolManager();
        var revalidator = new DestinationPolicyPoolRevalidator(policy, poolManager, clock);
        var staleButSameFingerprint = assessment(NOW.minusSeconds(1), "1.2.3.4");
        var binding = new PoolDestinationBinding(poolKey("test-policy-v1"), staleButSameFingerprint);

        DestinationAssessment result = revalidator.ensureFreshForBorrow(binding, request());

        assertThat(result).isEqualTo(freshSameAddress);
        assertThat(policy.callCount).isEqualTo(1);
        assertThat(poolManager.drainCalls).isEmpty();
    }

    @Test
    void anExpiredBindingWithAChangedAddressSetIsQuarantinedAndDrained() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var freshDifferentAddress = assessment(NOW.plusSeconds(60), "9.9.9.9"); // DNS rebound
        var policy = new RecordingPolicy(freshDifferentAddress);
        var poolManager = new RecordingPoolManager();
        var revalidator = new DestinationPolicyPoolRevalidator(policy, poolManager, clock);
        var stale = assessment(NOW.minusSeconds(1), "1.2.3.4");
        var key = poolKey("test-policy-v1");
        var binding = new PoolDestinationBinding(key, stale);

        assertThatThrownBy(() -> revalidator.ensureFreshForBorrow(binding, request()))
                .isInstanceOf(DestinationValidationException.class)
                .extracting(ex -> ((DestinationValidationException) ex).reason())
                .isEqualTo(DestinationDenialReason.BINDING_STALE);
        assertThat(poolManager.drainCalls).hasSize(1);
        assertThat(poolManager.drainCalls.get(0).key()).isEqualTo(key);
        assertThat(poolManager.drainCalls.get(0).reason()).isEqualTo(PoolDrainReason.POLICY_CHANGED);
    }

    @Test
    void anExpiredBindingWhoseResolutionFailsIsQuarantinedAndDrainedAndThePolicyFailurePropagates() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var policy = new RecordingPolicy(null);
        var poolManager = new RecordingPoolManager();
        var revalidator = new DestinationPolicyPoolRevalidator(policy, poolManager, clock);
        var stale = assessment(NOW.minusSeconds(1), "1.2.3.4");
        var key = poolKey("test-policy-v1");
        var binding = new PoolDestinationBinding(key, stale);

        assertThatThrownBy(() -> revalidator.ensureFreshForBorrow(binding, request()))
                .isInstanceOf(DestinationValidationException.class)
                .extracting(ex -> ((DestinationValidationException) ex).reason())
                .isEqualTo(DestinationDenialReason.PROHIBITED_ADDRESS); // the policy's own failure, propagated as-is
        assertThat(poolManager.drainCalls).hasSize(1);
        assertThat(poolManager.drainCalls.get(0).reason()).isEqualTo(PoolDrainReason.POLICY_CHANGED);
    }

    /**
     * Regression for a prior review finding: {@code PoolDestinationBinding} is an immutable record
     * with no quarantine marker, so a naive re-check of the fingerprint alone could "resurrect" a
     * binding that was already drained for cause if DNS happened to flap back to its original
     * answer. This proves a binding drained once for a changed address set stays rejected on a
     * second presentation of the SAME key, even once DNS has reverted to match the ORIGINAL
     * (now-stale-again) fingerprint -- it must not be treated as fresh again.
     */
    @Test
    void aBindingDrainedForCauseIsNeverResurrectedEvenIfDnsRevertsToTheOriginalAnswer() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var reboundThenReverted = new RecordingPolicy(null); // configured per call below
        var poolManager = new RecordingPoolManager();
        var revalidator = new DestinationPolicyPoolRevalidator(reboundThenReverted, poolManager, clock);
        var stale = assessment(NOW.minusSeconds(1), "1.2.3.4");
        var key = poolKey("test-policy-v1");
        var binding = new PoolDestinationBinding(key, stale);

        // First presentation: DNS has rebound to a different address; drained and quarantined.
        reboundThenReverted.toReturn = assessment(NOW.plusSeconds(60), "9.9.9.9");
        assertThatThrownBy(() -> revalidator.ensureFreshForBorrow(binding, request()))
                .isInstanceOf(DestinationValidationException.class)
                .extracting(ex -> ((DestinationValidationException) ex).reason())
                .isEqualTo(DestinationDenialReason.BINDING_STALE);
        assertThat(poolManager.drainCalls).hasSize(1);

        // Second presentation of the exact same key: DNS has reverted to the ORIGINAL fingerprint
        // ("1.2.3.4"), which would naively look "unchanged" again -- but this key was already
        // quarantined and must stay rejected without even attempting a new resolution.
        reboundThenReverted.toReturn = assessment(NOW.plusSeconds(60), "1.2.3.4");
        int callsBeforeSecondAttempt = reboundThenReverted.callCount;
        assertThatThrownBy(() -> revalidator.ensureFreshForBorrow(binding, request()))
                .isInstanceOf(DestinationValidationException.class)
                .extracting(ex -> ((DestinationValidationException) ex).reason())
                .isEqualTo(DestinationDenialReason.BINDING_STALE);
        assertThat(reboundThenReverted.callCount).isEqualTo(callsBeforeSecondAttempt); // no new DNS traffic spent
    }

    private static DestinationRequest request() {
        return new DestinationRequest(
                WORKSPACE,
                CONNECTION,
                new ConnectionVersion(1),
                DatabaseKind.POSTGRESQL,
                ConnectionPurpose.PHYSICAL_POOL_CONNECTION,
                new StructuredEndpoint("db.example.com", 5432, "analytics"),
                new Deadline(NOW.plusSeconds(300)));
    }

    private static PoolKey poolKey(String destinationPolicyVersion) {
        return new PoolKey(WORKSPACE, CONNECTION, new ConnectionVersion(1), 1, destinationPolicyVersion);
    }

    private static DestinationAssessment assessment(Instant expiresAt, String ipv4) {
        String[] parts = ipv4.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i]);
        }
        return new DestinationAssessment(
                "db.example.com",
                "db.example.com",
                5432,
                Set.of(new VettedAddress(AddressFamily.IPV4, bytes)),
                "fingerprint-of-" + ipv4,
                "test-policy-v1",
                NOW.minusSeconds(120),
                expiresAt);
    }

    private static final class RecordingPolicy implements DestinationPolicy {
        private DestinationAssessment toReturn;
        int callCount;

        RecordingPolicy(DestinationAssessment toReturn) {
            this.toReturn = toReturn;
        }

        @Override
        public DestinationAssessment assess(DestinationRequest request) {
            callCount++;
            if (toReturn == null) {
                throw new DestinationValidationException(
                        DestinationDenialReason.PROHIBITED_ADDRESS, "simulated policy failure");
            }
            return toReturn;
        }
    }

    private static final class RecordingPoolManager implements TargetPoolManager {
        record DrainCall(PoolKey key, PoolDrainReason reason, Deadline deadline) {}

        final List<DrainCall> drainCalls = new ArrayList<>();

        @Override
        public void stopNewBorrowsAndDrain(PoolKey key, PoolDrainReason reason, Deadline deadline) {
            drainCalls.add(new DrainCall(key, reason, deadline));
        }
    }
}
