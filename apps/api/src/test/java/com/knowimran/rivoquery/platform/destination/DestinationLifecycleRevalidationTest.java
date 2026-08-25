package com.knowimran.rivoquery.platform.destination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.destination.AddressFamily;
import com.knowimran.rivoquery.connection.application.destination.ConnectionPurpose;
import com.knowimran.rivoquery.connection.application.destination.DestinationAssessment;
import com.knowimran.rivoquery.connection.application.destination.DestinationRequest;
import com.knowimran.rivoquery.connection.application.destination.StructuredEndpoint;
import com.knowimran.rivoquery.connection.pool.PoolDrainReason;
import com.knowimran.rivoquery.connection.pool.PoolKey;
import com.knowimran.rivoquery.connection.pool.TargetPoolManager;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.platform.destination.dns.DnsAnswer;
import com.knowimran.rivoquery.platform.destination.dns.DnsResolution;
import com.knowimran.rivoquery.platform.destination.dns.FakeDnsResolver;
import com.knowimran.rivoquery.platform.destination.policy.OperatorPrivateAllowlist;
import com.knowimran.rivoquery.platform.destination.pool.DestinationPolicyPoolRevalidator;
import com.knowimran.rivoquery.platform.destination.pool.PoolDestinationBinding;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministically changes the DNS answer between registration, a later pool borrow, and a later
 * physical-connection dial, proving revalidation happens at each point and that a changed/expired
 * binding blocks the next borrow (pool quarantined and drained) rather than letting a caller dial
 * a stale, no-longer-vetted address.
 */
class DestinationLifecycleRevalidationTest {
    private static final String ULID = "01J6MZ4H9ST5N7YQ2C8W3V0KFX";
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_" + ULID);
    private static final ConnectionId CONNECTION = new ConnectionId("con_" + ULID);
    private static final Instant T0 = Instant.parse("2026-08-20T12:00:00Z"); // registration time
    private static final byte[] ORIGINAL_ADDRESS = {1, 1, 1, 1};
    private static final byte[] REBOUND_ADDRESS = {2, 2, 2, 2};

    @Test
    void revalidatesAtRegistrationPoolBorrowAndEveryPhysicalConnection() {
        var resolver = new FakeDnsResolver();
        var properties = properties();
        var poolManager = new RecordingPoolManager();
        var request = request();

        // --- Registration: DNS currently answers with the original address, TTL 60s. ---
        resolver.enqueue(dnsResolution(ORIGINAL_ADDRESS, 60));
        DestinationAssessment registration = policyAt(T0, resolver, properties).assess(request);
        assertThat(addressBytes(registration)).isEqualTo(ORIGINAL_ADDRESS);
        assertThat(resolver.requestCount()).isEqualTo(1);

        var poolKey = new PoolKey(WORKSPACE, CONNECTION, new ConnectionVersion(1), 1, properties.policyVersion());
        var binding = new PoolDestinationBinding(poolKey, registration);

        // --- Pool use, still within the TTL window: no new DNS traffic, binding reused as-is. ---
        Instant withinTtl = T0.plusSeconds(10);
        var revalidatorWithinTtl = revalidatorAt(withinTtl, resolver, properties, poolManager);
        DestinationAssessment stillFresh = revalidatorWithinTtl.ensureFreshForBorrow(binding, request());
        assertThat(addressBytes(stillFresh)).isEqualTo(ORIGINAL_ADDRESS);
        assertThat(resolver.requestCount()).isEqualTo(1); // no new query yet
        assertThat(poolManager.drainCalls).isEmpty();

        // --- DNS changes underneath (rebinding) while the pool keeps its now-stale binding. ---
        resolver.enqueue(dnsResolution(REBOUND_ADDRESS, 60));

        // --- Pool use, after the TTL has expired: revalidation detects the change and fails
        //     closed, quarantining and draining the pool instead of lending a stale connection. ---
        Instant afterTtlExpiry = T0.plusSeconds(90);
        var revalidatorAfterExpiry = revalidatorAt(afterTtlExpiry, resolver, properties, poolManager);
        assertThatThrownBy(() -> revalidatorAfterExpiry.ensureFreshForBorrow(binding, request()))
                .isInstanceOf(DestinationValidationException.class)
                .extracting(ex -> ((DestinationValidationException) ex).reason())
                .isEqualTo(DestinationDenialReason.BINDING_STALE);
        assertThat(resolver.requestCount()).isEqualTo(2);
        assertThat(poolManager.drainCalls).hasSize(1);
        assertThat(poolManager.drainCalls.get(0).key()).isEqualTo(poolKey);
        assertThat(poolManager.drainCalls.get(0).reason()).isEqualTo(PoolDrainReason.POLICY_CHANGED);

        // --- Every physical connection obtains its own fresh capability: a brand-new pool built
        //     after the drain immediately reflects the current (rebound) DNS answer, never the
        //     original, no-longer-current address. ---
        resolver.enqueue(dnsResolution(REBOUND_ADDRESS, 60));
        Instant physicalConnectionTime = T0.plusSeconds(95);
        DestinationAssessment physicalConnection = policyAt(physicalConnectionTime, resolver, properties).assess(request());
        assertThat(addressBytes(physicalConnection)).isEqualTo(REBOUND_ADDRESS);
        assertThat(addressBytes(physicalConnection)).isNotEqualTo(addressBytes(registration));
        assertThat(resolver.requestCount()).isEqualTo(3);
    }

    private static byte[] addressBytes(DestinationAssessment assessment) {
        return assessment.vettedAddresses().iterator().next().addressBytes();
    }

    private static DnsResolution dnsResolution(byte[] address, long ttlSeconds) {
        return new DnsResolution(List.of(), List.of(new DnsAnswer(AddressFamily.IPV4, address, ttlSeconds)));
    }

    private static DestinationEnforcementPolicy policyAt(
            Instant now, FakeDnsResolver resolver, DestinationPolicyProperties properties) {
        return new DestinationEnforcementPolicy(
                resolver, properties, new OperatorPrivateAllowlist(List.of()), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static DestinationPolicyPoolRevalidator revalidatorAt(
            Instant now, FakeDnsResolver resolver, DestinationPolicyProperties properties, TargetPoolManager poolManager) {
        return new DestinationPolicyPoolRevalidator(
                policyAt(now, resolver, properties), poolManager, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static DestinationPolicyProperties properties() {
        return new DestinationPolicyProperties(
                Duration.ofSeconds(2), 8, 300, 5, "lifecycle-policy-v1", null, List.of());
    }

    private static DestinationRequest request() {
        return new DestinationRequest(
                WORKSPACE,
                CONNECTION,
                new ConnectionVersion(1),
                DatabaseKind.POSTGRESQL,
                ConnectionPurpose.PHYSICAL_POOL_CONNECTION,
                new StructuredEndpoint("db.example.com", 5432, "analytics"),
                new Deadline(T0.plusSeconds(600)));
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
