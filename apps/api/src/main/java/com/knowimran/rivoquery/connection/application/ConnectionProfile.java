package com.knowimran.rivoquery.connection.application;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.application.destination.StructuredEndpoint;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Non-secret, display/administration-only connection metadata: name,
 * endpoint view, TLS summary, connector options, object access policy, and
 * limits.
 *
 * <p>The configuration-shaped fields ({@code tlsMode}, {@code
 * objectPolicy}, {@code limits}, {@code tcpKeepAlive}) are recorded and
 * returned by the REST layer but do not yet gate connector behavior - they
 * are display-only until Phase 3's {@code execution}/{@code querypolicy}
 * consume them. Only the secret/TLS PEM material stays out of this record
 * entirely (just the "was a custom CA/client cert supplied" flags).
 *
 * <p><b>Why this exists (P2-130 deviation, see docs/IMPLEMENTATION_TASKS.md
 * P2-130 evidence for the full explanation):</b> {@link
 * com.knowimran.rivoquery.connection.DatabaseConnection} - the P2-100
 * lifecycle aggregate - has no persisted columns for any of these fields
 * (only kind/state/version/secret bookkeeping); P2-100's own evidence
 * documents this as a deliberate, known gap pending a future reviewed schema
 * change. The OpenAPI contract's {@code ConnectionDetail}/{@code
 * ConnectionSummary}/{@code CreateConnectionRequest}/{@code
 * UpdateConnectionRequest} schemas all require these fields, so a REST layer
 * cannot be contract-compliant without storing them somewhere. This type and
 * its {@link ConnectionProfileRepository} port/{@code
 * JdbcConnectionProfileRepository} adapter are a new, additive,
 * non-destructive persistence extension built by P2-130 to close that gap
 * without touching {@code DatabaseConnection}, {@code ConnectionRepository},
 * or any file under {@code connection.application.lifecycle} (all
 * off-limits per this task's own instructions). No secret or TLS PEM
 * material is ever persisted here - only the boolean "was a custom CA/client
 * certificate supplied" flags the {@code TlsSummary} schema itself requires.
 * Writing a profile row is best-effort and happens only after the owning
 * {@link com.knowimran.rivoquery.connection.DatabaseConnection} mutation has
 * already durably committed (mirroring the existing best-effort
 * discovery-enqueue/pool-drain pattern in {@code ConnectionLifecycleService}
 * rather than being made transactional with it, since this table is
 * intentionally outside that saga's own transaction boundary).
 */
public record ConnectionProfile(
        WorkspaceId workspaceId,
        ConnectionId connectionId,
        String name,
        StructuredEndpoint endpoint,
        ConnectionTlsMode tlsMode,
        boolean customCaConfigured,
        boolean clientCertificateConfigured,
        boolean tcpKeepAlive,
        ConnectionObjectPolicy objectPolicy,
        ConnectionLimits limits,
        Instant createdAt,
        Instant updatedAt) {
    public ConnectionProfile {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(tlsMode, "tlsMode");
        Objects.requireNonNull(objectPolicy, "objectPolicy");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("name must be 1-120 characters");
        }
    }

    public ConnectionProfile withName(String newName) {
        return new ConnectionProfile(workspaceId, connectionId, newName, endpoint, tlsMode,
                customCaConfigured, clientCertificateConfigured, tcpKeepAlive, objectPolicy, limits,
                createdAt, updatedAt);
    }

    public ConnectionProfile withEndpoint(StructuredEndpoint newEndpoint) {
        return new ConnectionProfile(workspaceId, connectionId, name, newEndpoint, tlsMode,
                customCaConfigured, clientCertificateConfigured, tcpKeepAlive, objectPolicy, limits,
                createdAt, updatedAt);
    }

    public ConnectionProfile withTlsMode(ConnectionTlsMode newTlsMode) {
        return new ConnectionProfile(workspaceId, connectionId, name, endpoint, newTlsMode,
                customCaConfigured, clientCertificateConfigured, tcpKeepAlive, objectPolicy, limits,
                createdAt, updatedAt);
    }

    public ConnectionProfile withTcpKeepAlive(boolean newTcpKeepAlive) {
        return new ConnectionProfile(workspaceId, connectionId, name, endpoint, tlsMode,
                customCaConfigured, clientCertificateConfigured, newTcpKeepAlive, objectPolicy, limits,
                createdAt, updatedAt);
    }

    public ConnectionProfile withObjectPolicy(ConnectionObjectPolicy newObjectPolicy) {
        return new ConnectionProfile(workspaceId, connectionId, name, endpoint, tlsMode,
                customCaConfigured, clientCertificateConfigured, tcpKeepAlive, newObjectPolicy, limits,
                createdAt, updatedAt);
    }

    public ConnectionProfile withLimits(ConnectionLimits newLimits) {
        return new ConnectionProfile(workspaceId, connectionId, name, endpoint, tlsMode,
                customCaConfigured, clientCertificateConfigured, tcpKeepAlive, objectPolicy, newLimits,
                createdAt, updatedAt);
    }

    /**
     * Bumps only {@code updatedAt}, leaving {@code createdAt} untouched -
     * used once by {@code ConnectionController#updateConnection} after
     * applying every field-level {@code with*} change from a PATCH body (S3
     * fix: real timestamps, not {@code OffsetDateTime.now()} fabricated at
     * response-serialization time).
     */
    public ConnectionProfile withUpdatedAt(Instant newUpdatedAt) {
        return new ConnectionProfile(workspaceId, connectionId, name, endpoint, tlsMode,
                customCaConfigured, clientCertificateConfigured, tcpKeepAlive, objectPolicy, limits,
                createdAt, newUpdatedAt);
    }

    /**
     * A defensive fallback for the rare case where the owning connection
     * exists but its profile row is missing (e.g. the best-effort profile
     * write after {@code create} failed - see class Javadoc: this table is
     * intentionally outside the lifecycle saga's own transaction). Every
     * field is an obviously-synthetic placeholder, never fabricated real
     * data, so a caller cannot mistake it for the connection's actual
     * configuration - {@code createdAt}/{@code updatedAt} use {@link
     * Instant#EPOCH} rather than "now" for the same reason: an honestly
     * unavailable/unknown timestamp, not a fabricated real one.
     */
    public static ConnectionProfile placeholder(WorkspaceId workspaceId, ConnectionId connectionId) {
        return new ConnectionProfile(
                workspaceId, connectionId, "(profile unavailable)",
                new StructuredEndpoint("profile-unavailable.invalid", 1, "unavailable"),
                ConnectionTlsMode.VERIFY_FULL, false, false, true,
                new ConnectionObjectPolicy(Set.of("unavailable"), Set.of()), ConnectionLimits.defaults(),
                Instant.EPOCH, Instant.EPOCH);
    }
}
