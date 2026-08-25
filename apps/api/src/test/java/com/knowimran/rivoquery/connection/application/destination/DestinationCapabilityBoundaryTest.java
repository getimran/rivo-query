package com.knowimran.rivoquery.connection.application.destination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.destination.DestinationCapabilityAuthority.ValidatedDestination;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DestinationCapabilityBoundaryTest {
    private static final String ULID = "01J6MZ4H9ST5N7YQ2C8W3V0KFX";
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_" + ULID);
    private static final ConnectionId CONNECTION = new ConnectionId("con_" + ULID);
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void capabilityIsSealedToOnePrivateFinalAuthorityImplementation() {
        assertThat(ValidatedDestination.class.isSealed()).isTrue();
        assertThat(ValidatedDestination.class.getPermittedSubclasses()).hasSize(1);
        var implementation = ValidatedDestination.class.getPermittedSubclasses()[0];
        assertThat(implementation.getEnclosingClass()).isEqualTo(DestinationCapabilityAuthority.class);
        assertThat(Modifier.isPrivate(implementation.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(implementation.getModifiers())).isTrue();
        assertThat(implementation.getDeclaredConstructors()).allMatch(
                constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Serializable.class.isAssignableFrom(ValidatedDestination.class)).isFalse();
        assertThatThrownBy(() -> new ObjectMapper().readValue("{}", ValidatedDestination.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void authorityMintsCompleteExactRedactedCapability() {
        var request = request(WORKSPACE, new ConnectionVersion(7), new Deadline(NOW.plusSeconds(20)));
        var authority = authority(assessment(NOW.plusSeconds(60)));
        var capability = authority.validate(request);

        assertThat(capability.workspaceId()).isEqualTo(WORKSPACE);
        assertThat(capability.connectionId()).isEqualTo(CONNECTION);
        assertThat(capability.connectionVersion()).isEqualTo(new ConnectionVersion(7));
        assertThat(capability.connectorKind()).isEqualTo(DatabaseKind.POSTGRESQL);
        assertThat(capability.purpose()).isEqualTo(ConnectionPurpose.PHYSICAL_POOL_CONNECTION);
        assertThat(capability.canonicalHostname()).isEqualTo("db.example.com");
        assertThat(capability.originalTlsHostname()).isEqualTo("DB.Example.com");
        assertThat(capability.port()).isEqualTo(5432);
        assertThat(capability.vettedAddresses()).containsExactly(
                new VettedAddress(AddressFamily.IPV4, new byte[] {8, 8, 8, 8}));
        assertThat(capability.addressSetFingerprint()).isEqualTo("fingerprint-v1");
        assertThat(capability.policyVersion()).isEqualTo("policy-v4");
        assertThat(capability.resolvedAt()).isEqualTo(NOW);
        assertThat(capability.expiresAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(capability.operationDeadline()).isEqualTo(request.deadline());
        assertThat(capability.nonce()).isEqualTo("destination-nonce");
        assertThat(capability.toString()).isEqualTo("ValidatedDestination[REDACTED]");
        capability.requireValid(request, "policy-v4", CLOCK);
    }

    @Test
    void anyBindingPolicyOrTimeMismatchInvalidatesCapability() {
        var deadline = new Deadline(NOW.plusSeconds(20));
        var request = request(WORKSPACE, new ConnectionVersion(7), deadline);
        var capability = authority(assessment(NOW.plusSeconds(10))).validate(request);
        var foreign = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY");

        assertThatThrownBy(() -> capability.requireValid(
                        request(foreign, new ConnectionVersion(7), deadline), "policy-v4", CLOCK))
                .isInstanceOf(DestinationAuthorizationException.class);
        assertThatThrownBy(() -> capability.requireValid(
                        request(WORKSPACE, new ConnectionVersion(8), deadline), "policy-v4", CLOCK))
                .isInstanceOf(DestinationAuthorizationException.class);
        assertThatThrownBy(() -> capability.requireValid(request, "policy-v5", CLOCK))
                .isInstanceOf(DestinationAuthorizationException.class);
        assertThatThrownBy(() -> capability.requireValid(
                        request,
                        "policy-v4",
                        Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC)))
                .isInstanceOf(DestinationAuthorizationException.class);
    }

    @Test
    void authorityRejectsMismatchedPortFutureResolutionAndExpiredAssessment() {
        var request = request(WORKSPACE, new ConnectionVersion(1), new Deadline(NOW.plusSeconds(20)));
        var wrongPort = new DestinationAssessment(
                "db.example.com", "DB.Example.com", 3306,
                addresses(), "fingerprint-v1", "policy-v4", NOW, NOW.plusSeconds(5));
        var future = new DestinationAssessment(
                "db.example.com", "DB.Example.com", 5432,
                addresses(), "fingerprint-v1", "policy-v4", NOW.plusSeconds(1), NOW.plusSeconds(5));

        assertThatThrownBy(() -> authority(wrongPort).validate(request))
                .isInstanceOf(DestinationAuthorizationException.class);
        assertThatThrownBy(() -> authority(future).validate(request))
                .isInstanceOf(DestinationAuthorizationException.class);
        assertThatThrownBy(() -> new DestinationAssessment(
                        "db.example.com", "DB.Example.com", 5432,
                        Set.of(), "fingerprint-v1", "policy-v4", NOW, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DestinationCapabilityAuthority authority(DestinationAssessment assessment) {
        return new DestinationCapabilityAuthority(request -> assessment, () -> "destination-nonce", CLOCK);
    }

    private static DestinationRequest request(
            WorkspaceId workspaceId, ConnectionVersion version, Deadline deadline) {
        return new DestinationRequest(
                workspaceId,
                CONNECTION,
                version,
                DatabaseKind.POSTGRESQL,
                ConnectionPurpose.PHYSICAL_POOL_CONNECTION,
                new StructuredEndpoint("DB.Example.com", 5432, "analytics"),
                deadline);
    }

    private static DestinationAssessment assessment(Instant expiresAt) {
        return new DestinationAssessment(
                "db.example.com",
                "DB.Example.com",
                5432,
                addresses(),
                "fingerprint-v1",
                "policy-v4",
                NOW,
                expiresAt);
    }

    private static Set<VettedAddress> addresses() {
        return Set.of(new VettedAddress(
                AddressFamily.IPV4, new byte[] {8, 8, 8, 8}));
    }
}
