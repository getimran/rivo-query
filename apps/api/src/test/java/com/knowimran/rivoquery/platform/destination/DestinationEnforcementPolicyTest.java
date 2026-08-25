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
import com.knowimran.rivoquery.connection.application.destination.VettedAddress;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.platform.destination.dns.DnsAnswer;
import com.knowimran.rivoquery.platform.destination.dns.DnsResolution;
import com.knowimran.rivoquery.platform.destination.dns.DnsResolutionException;
import com.knowimran.rivoquery.platform.destination.dns.FakeDnsResolver;
import com.knowimran.rivoquery.platform.destination.policy.IpLiteral;
import com.knowimran.rivoquery.platform.destination.policy.OperatorPrivateAllowlist;
import com.knowimran.rivoquery.platform.destination.policy.PrivateDestinationRule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class DestinationEnforcementPolicyTest {
    private static final String ULID = "01J6MZ4H9ST5N7YQ2C8W3V0KFX";
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_" + ULID);
    private static final ConnectionId CONNECTION = new ConnectionId("con_" + ULID);
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void literalIpHostSkipsDnsAndUsesTheConfiguredMaxCapabilityTtl() {
        var resolver = new FakeDnsResolver();
        var policy = policy(resolver, properties(List.of()));
        var request = request(new StructuredEndpoint("93.184.216.34", 5432, "analytics"), NOW.plusSeconds(300));

        DestinationAssessment assessment = policy.assess(request);

        assertThat(resolver.requestCount()).isZero();
        assertThat(assessment.vettedAddresses())
                .containsExactly(new VettedAddress(AddressFamily.IPV4, new byte[] {93, (byte) 184, (byte) 216, 34}));
        assertThat(assessment.expiresAt()).isEqualTo(NOW.plusSeconds(60)); // maxCapabilityTtlSeconds
    }

    @Test
    void resolvesHostnameAndUsesTheEarliestTrustworthyTtlBoundedByTheConfiguredMax() {
        var resolver = FakeDnsResolver.alwaysReturning(new DnsResolution(
                List.of(),
                List.of(
                        answer(AddressFamily.IPV4, new byte[] {93, (byte) 184, (byte) 216, 34}, 30),
                        answer(AddressFamily.IPV4, new byte[] {93, (byte) 184, (byte) 216, 35}, 120))));
        var policy = policy(resolver, properties(List.of()));
        var request = request(new StructuredEndpoint("DB.Example.COM", 5432, "analytics"), NOW.plusSeconds(300));

        DestinationAssessment assessment = policy.assess(request);

        assertThat(assessment.expiresAt()).isEqualTo(NOW.plusSeconds(30)); // earliest of 30/120, under the 60s cap
        assertThat(assessment.canonicalHostname()).isEqualTo("db.example.com");
    }

    @Test
    void resolvesAnIpv6LiteralHostThroughTheFullAssessPath() {
        var resolver = new FakeDnsResolver();
        var policy = policy(resolver, properties(List.of()));
        var request = request(new StructuredEndpoint("2606:4700:4700::1111", 5432, "analytics"), NOW.plusSeconds(300));

        DestinationAssessment assessment = policy.assess(request);

        assertThat(resolver.requestCount()).isZero(); // strict literal: no DNS/rebinding exposure
        assertThat(assessment.vettedAddresses())
                .containsExactly(IpLiteral.tryParse("2606:4700:4700::1111").orElseThrow());
        assertThat(assessment.expiresAt()).isEqualTo(NOW.plusSeconds(60)); // maxCapabilityTtlSeconds
    }

    @Test
    void capabilityExpiryIsClampedToTheCallersDeadlineWhenShorterThanTheDnsTtlAndThePolicyCap() {
        var resolver = FakeDnsResolver.alwaysReturning(new DnsResolution(
                List.of(), List.of(answer(AddressFamily.IPV4, new byte[] {93, (byte) 184, (byte) 216, 34}, 3600))));
        var policy = policy(resolver, properties(List.of()));
        var request = request(new StructuredEndpoint("db.example.com", 5432, "analytics"), NOW.plusSeconds(10));

        DestinationAssessment assessment = policy.assess(request);

        // The caller's own deadline (10s) is shorter than both the 3600s DNS TTL and the 60s
        // policy-configured cap, and must be the binding bound: the capability must never outlive
        // the request that vetted it.
        assertThat(assessment.expiresAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void untrustworthyTtlUsesTheConservativeFallback() {
        var resolver = FakeDnsResolver.alwaysReturning(new DnsResolution(
                List.of(), List.of(answer(AddressFamily.IPV4, new byte[] {93, (byte) 184, (byte) 216, 34}, 0))));
        var policy = policy(resolver, properties(List.of()));
        var request = request(new StructuredEndpoint("db.example.com", 5432, "analytics"), NOW.plusSeconds(300));

        DestinationAssessment assessment = policy.assess(request);

        assertThat(assessment.expiresAt()).isEqualTo(NOW.plusSeconds(5)); // fallbackTtlSeconds
    }

    @Test
    void deniesAProhibitedAddress() {
        var resolver = FakeDnsResolver.alwaysReturning(new DnsResolution(
                List.of(), List.of(answer(AddressFamily.IPV4, new byte[] {127, 0, 0, 1}, 60))));
        var policy = policy(resolver, properties(List.of()));
        var request = request(new StructuredEndpoint("db.example.com", 5432, "analytics"), NOW.plusSeconds(300));

        assertThatThrownBy(() -> policy.assess(request))
                .isInstanceOf(DestinationValidationException.class)
                .extracting(ex -> ((DestinationValidationException) ex).reason())
                .isEqualTo(DestinationDenialReason.PROHIBITED_ADDRESS);
    }

    @Test
    void deniesAMixedAllowedAndProhibitedAnswerSetRatherThanPickingTheAllowedOne() {
        var resolver = FakeDnsResolver.alwaysReturning(new DnsResolution(
                List.of(),
                List.of(
                        answer(AddressFamily.IPV4, new byte[] {93, (byte) 184, (byte) 216, 34}, 60), // public, fine
                        answer(AddressFamily.IPV4, new byte[] {127, 0, 0, 1}, 60)))); // loopback, prohibited
        var policy = policy(resolver, properties(List.of()));
        var request = request(new StructuredEndpoint("db.example.com", 5432, "analytics"), NOW.plusSeconds(300));

        assertThatThrownBy(() -> policy.assess(request))
                .isInstanceOf(DestinationValidationException.class)
                .extracting(ex -> ((DestinationValidationException) ex).reason())
                .isEqualTo(DestinationDenialReason.PROHIBITED_ADDRESS);
    }

    @Test
    void deniesAPrivateAddressWithoutAMatchingOperatorRule() {
        var resolver = FakeDnsResolver.alwaysReturning(
                new DnsResolution(List.of(), List.of(answer(AddressFamily.IPV4, new byte[] {10, 0, 0, 5}, 60))));
        var policy = policy(resolver, properties(List.of()));
        var request = request(new StructuredEndpoint("db.internal.example.com", 5432, "analytics"), NOW.plusSeconds(300));

        assertThatThrownBy(() -> policy.assess(request))
                .isInstanceOf(DestinationValidationException.class)
                .extracting(ex -> ((DestinationValidationException) ex).reason())
                .isEqualTo(DestinationDenialReason.PROHIBITED_ADDRESS);
    }

    @Test
    void allowsAPrivateAddressWhenAnOperatorRuleMatchesNamePortAndEveryAddress() {
        var resolver = FakeDnsResolver.alwaysReturning(
                new DnsResolution(List.of(), List.of(answer(AddressFamily.IPV4, new byte[] {10, 0, 0, 5}, 60))));
        var rule = new PrivateDestinationRule(
                DatabaseKind.POSTGRESQL, "db.internal.example.com", 5432, 5432, List.of("10.0.0.0/8"));
        var policy = policy(resolver, properties(List.of(rule)));
        var request = request(new StructuredEndpoint("db.internal.example.com", 5432, "analytics"), NOW.plusSeconds(300));

        DestinationAssessment assessment = policy.assess(request);

        assertThat(assessment.vettedAddresses())
                .containsExactly(new VettedAddress(AddressFamily.IPV4, new byte[] {10, 0, 0, 5}));
    }

    @Test
    void deniesAPrivateAddressWhenOnlySomeAnswersMatchTheOperatorRule() {
        var resolver = FakeDnsResolver.alwaysReturning(new DnsResolution(
                List.of(),
                List.of(
                        answer(AddressFamily.IPV4, new byte[] {10, 0, 0, 5}, 60),
                        answer(AddressFamily.IPV4, new byte[] {10, 99, 0, 5}, 60))));
        var rule = new PrivateDestinationRule(
                DatabaseKind.POSTGRESQL, "db.internal.example.com", 5432, 5432, List.of("10.0.0.0/16"));
        var policy = policy(resolver, properties(List.of(rule)));
        var request = request(new StructuredEndpoint("db.internal.example.com", 5432, "analytics"), NOW.plusSeconds(300));

        assertThatThrownBy(() -> policy.assess(request))
                .isInstanceOf(DestinationValidationException.class)
                .extracting(ex -> ((DestinationValidationException) ex).reason())
                .isEqualTo(DestinationDenialReason.PROHIBITED_ADDRESS);
    }

    @Test
    void propagatesEveryDnsFailClosedReasonAsADestinationValidationException() {
        var resolver = new FakeDnsResolver();
        resolver.enqueueFailure(
                new DnsResolutionException(DestinationDenialReason.TRUNCATED_RESPONSE, "truncated"));
        var policy = policy(resolver, properties(List.of()));
        var request = request(new StructuredEndpoint("db.example.com", 5432, "analytics"), NOW.plusSeconds(300));

        assertThatThrownBy(() -> policy.assess(request))
                .isInstanceOf(DestinationValidationException.class)
                .extracting(ex -> ((DestinationValidationException) ex).reason())
                .isEqualTo(DestinationDenialReason.TRUNCATED_RESPONSE);
    }

    @Test
    void deniesWhenTheCallersDeadlineIsAlreadyExhaustedWithoutQueryingDns() {
        var resolver = new FakeDnsResolver();
        var policy = policy(resolver, properties(List.of()));
        var request = request(new StructuredEndpoint("db.example.com", 5432, "analytics"), NOW.minusSeconds(1));

        assertThatThrownBy(() -> policy.assess(request))
                .isInstanceOf(DestinationValidationException.class)
                .extracting(ex -> ((DestinationValidationException) ex).reason())
                .isEqualTo(DestinationDenialReason.DEADLINE_EXCEEDED);
        assertThat(resolver.requestCount()).isZero();
    }

    @Test
    void everyCallPerformsAFreshUncachedResolutionReflectingTheCurrentDnsAnswer() {
        var resolver = new FakeDnsResolver();
        resolver.enqueue(new DnsResolution(
                List.of(), List.of(answer(AddressFamily.IPV4, new byte[] {93, (byte) 184, (byte) 216, 34}, 60))));
        resolver.enqueue(new DnsResolution(
                List.of(), List.of(answer(AddressFamily.IPV4, new byte[] {(byte) 203, 0, 114, 5}, 60))));
        var policy = policy(resolver, properties(List.of()));
        var request = request(new StructuredEndpoint("db.example.com", 5432, "analytics"), NOW.plusSeconds(300));

        DestinationAssessment first = policy.assess(request);
        DestinationAssessment second = policy.assess(request);

        assertThat(resolver.requestCount()).isEqualTo(2);
        assertThat(first.vettedAddresses()).isNotEqualTo(second.vettedAddresses());
        assertThat(first.addressSetFingerprint()).isNotEqualTo(second.addressSetFingerprint());
    }

    @Test
    void preservesTheOriginalHostnameDistinctlyFromTheCanonicalHostnameForTlsIdentity() {
        var resolver = FakeDnsResolver.alwaysReturning(
                new DnsResolution(List.of(), List.of(answer(AddressFamily.IPV4, new byte[] {1, 2, 3, 4}, 60))));
        var policy = policy(resolver, properties(List.of()));
        var request = request(new StructuredEndpoint("DB.Example.COM", 5432, "analytics"), NOW.plusSeconds(300));

        DestinationAssessment assessment = policy.assess(request);

        assertThat(assessment.canonicalHostname()).isEqualTo("db.example.com");
        assertThat(assessment.originalTlsHostname()).isEqualTo("DB.Example.COM");
        assertThat(assessment.canonicalHostname()).isNotEqualTo(assessment.originalTlsHostname());
    }

    @Test
    void fingerprintIsOrderIndependent() {
        var address1 = answer(AddressFamily.IPV4, new byte[] {1, 2, 3, 4}, 60);
        var address2 = answer(AddressFamily.IPV4, new byte[] {5, 6, 7, 8}, 60);
        var resolverAscending = FakeDnsResolver.alwaysReturning(new DnsResolution(List.of(), List.of(address1, address2)));
        var resolverDescending = FakeDnsResolver.alwaysReturning(new DnsResolution(List.of(), List.of(address2, address1)));
        var request = request(new StructuredEndpoint("db.example.com", 5432, "analytics"), NOW.plusSeconds(300));

        var fingerprint1 = policy(resolverAscending, properties(List.of())).assess(request).addressSetFingerprint();
        var fingerprint2 = policy(resolverDescending, properties(List.of())).assess(request).addressSetFingerprint();

        assertThat(fingerprint1).isEqualTo(fingerprint2);
    }

    @Test
    void stampsThePolicyVersionFromConfiguration() {
        var resolver = FakeDnsResolver.alwaysReturning(
                new DnsResolution(List.of(), List.of(answer(AddressFamily.IPV4, new byte[] {1, 2, 3, 4}, 60))));
        var properties = properties(List.of());
        var policy = policy(resolver, properties);
        var request = request(new StructuredEndpoint("db.example.com", 5432, "analytics"), NOW.plusSeconds(300));

        assertThat(policy.assess(request).policyVersion()).isEqualTo(properties.policyVersion());
    }

    private static DnsAnswer answer(AddressFamily family, byte[] bytes, long ttlSeconds) {
        return new DnsAnswer(family, bytes, ttlSeconds);
    }

    private static DestinationEnforcementPolicy policy(
            com.knowimran.rivoquery.platform.destination.dns.DnsResolver resolver, DestinationPolicyProperties properties) {
        return new DestinationEnforcementPolicy(
                resolver, properties, new OperatorPrivateAllowlist(properties.privateAllowlist().stream()
                        .map(rule -> new PrivateDestinationRule(
                                rule.connectorKind(), rule.hostname(), rule.minPort(), rule.maxPort(), rule.cidrs()))
                        .toList()),
                CLOCK);
    }

    private static DestinationPolicyProperties properties(List<PrivateDestinationRule> privateRules) {
        return new DestinationPolicyProperties(
                java.time.Duration.ofSeconds(2),
                8,
                60,
                5,
                "test-policy-v1",
                null,
                privateRules.stream()
                        .map(rule -> new DestinationPolicyProperties.PrivateRule(
                                rule.connectorKind(), rule.hostnameSuffixOrExactName(), rule.minPort(), rule.maxPort(), rule.cidrs()))
                        .toList());
    }

    private static DestinationRequest request(StructuredEndpoint endpoint, Instant deadlineExpiresAt) {
        return new DestinationRequest(
                WORKSPACE,
                CONNECTION,
                new ConnectionVersion(1),
                DatabaseKind.POSTGRESQL,
                ConnectionPurpose.PHYSICAL_POOL_CONNECTION,
                endpoint,
                new Deadline(deadlineExpiresAt));
    }
}
