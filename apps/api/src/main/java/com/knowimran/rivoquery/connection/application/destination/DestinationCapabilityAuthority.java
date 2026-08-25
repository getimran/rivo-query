package com.knowimran.rivoquery.connection.application.destination;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public final class DestinationCapabilityAuthority implements DestinationOperations {
    private final DestinationPolicy policy;
    private final DestinationNonceSource nonceSource;
    private final Clock clock;

    public DestinationCapabilityAuthority(
            DestinationPolicy policy, DestinationNonceSource nonceSource, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.nonceSource = Objects.requireNonNull(nonceSource, "nonceSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ValidatedDestination validate(DestinationRequest request) {
        Objects.requireNonNull(request, "request").deadline().requireUsable(clock);
        var assessment = Objects.requireNonNull(policy.assess(request), "assessment");
        if (assessment.port() != request.endpoint().port()
                || !assessment.originalTlsHostname().equals(request.endpoint().host())
                || assessment.resolvedAt().isAfter(clock.instant())
                || !clock.instant().isBefore(assessment.expiresAt())) {
            throw new DestinationAuthorizationException();
        }
        var expiresAt = assessment.expiresAt().isBefore(request.deadline().expiresAt())
                ? assessment.expiresAt()
                : request.deadline().expiresAt();
        String nonce = Objects.requireNonNull(nonceSource.newNonce(), "nonce");
        if (nonce.isBlank()) {
            throw new DestinationAuthorizationException();
        }
        return new Capability(
                request.workspaceId(),
                request.connectionId(),
                request.connectionVersion(),
                request.databaseKind(),
                request.purpose(),
                assessment.canonicalHostname(),
                assessment.originalTlsHostname(),
                assessment.port(),
                assessment.vettedAddresses(),
                assessment.addressSetFingerprint(),
                assessment.policyVersion(),
                assessment.resolvedAt(),
                expiresAt,
                request.deadline(),
                nonce);
    }

    public sealed interface ValidatedDestination permits Capability {
        WorkspaceId workspaceId();
        ConnectionId connectionId();
        ConnectionVersion connectionVersion();
        DatabaseKind connectorKind();
        ConnectionPurpose purpose();
        String canonicalHostname();
        String originalTlsHostname();
        int port();
        Set<VettedAddress> vettedAddresses();
        String addressSetFingerprint();
        String policyVersion();
        Instant resolvedAt();
        Instant expiresAt();
        Deadline operationDeadline();
        String nonce();

        void requireValid(DestinationRequest expected, String currentPolicyVersion, Clock clock);
    }

    private record Capability(
            WorkspaceId workspaceId,
            ConnectionId connectionId,
            ConnectionVersion connectionVersion,
            DatabaseKind connectorKind,
            ConnectionPurpose purpose,
            String canonicalHostname,
            String originalTlsHostname,
            int port,
            Set<VettedAddress> vettedAddresses,
            String addressSetFingerprint,
            String policyVersion,
            Instant resolvedAt,
            Instant expiresAt,
            Deadline operationDeadline,
            String nonce) implements ValidatedDestination {
        private Capability {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(connectionId, "connectionId");
            Objects.requireNonNull(connectionVersion, "connectionVersion");
            Objects.requireNonNull(connectorKind, "connectorKind");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(canonicalHostname, "canonicalHostname");
            Objects.requireNonNull(originalTlsHostname, "originalTlsHostname");
            vettedAddresses = Set.copyOf(vettedAddresses);
            Objects.requireNonNull(addressSetFingerprint, "addressSetFingerprint");
            Objects.requireNonNull(policyVersion, "policyVersion");
            Objects.requireNonNull(resolvedAt, "resolvedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(operationDeadline, "operationDeadline");
            Objects.requireNonNull(nonce, "nonce");
        }

        @Override
        public void requireValid(
                DestinationRequest expected, String currentPolicyVersion, Clock clock) {
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(currentPolicyVersion, "currentPolicyVersion");
            Objects.requireNonNull(clock, "clock");
            if (!workspaceId.equals(expected.workspaceId())
                    || !connectionId.equals(expected.connectionId())
                    || !connectionVersion.equals(expected.connectionVersion())
                    || connectorKind != expected.databaseKind()
                    || purpose != expected.purpose()
                    || !originalTlsHostname.equals(expected.endpoint().host())
                    || port != expected.endpoint().port()
                    || !policyVersion.equals(currentPolicyVersion)
                    || !operationDeadline.equals(expected.deadline())
                    || !clock.instant().isBefore(expiresAt)
                    || operationDeadline.isExpired(clock)) {
                throw new DestinationAuthorizationException();
            }
        }

        @Override
        public String toString() {
            return "ValidatedDestination[REDACTED]";
        }
    }
}
