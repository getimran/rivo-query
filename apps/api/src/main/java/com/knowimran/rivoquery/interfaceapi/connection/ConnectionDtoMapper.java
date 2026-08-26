package com.knowimran.rivoquery.interfaceapi.connection;

import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.application.ConnectionProfile;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import com.knowimran.rivoquery.interfaceapi.generated.model.ConnectionDetail;
import com.knowimran.rivoquery.interfaceapi.generated.model.ConnectionLimits;
import com.knowimran.rivoquery.interfaceapi.generated.model.ConnectionLinks;
import com.knowimran.rivoquery.interfaceapi.generated.model.ConnectionState;
import com.knowimran.rivoquery.interfaceapi.generated.model.ConnectionSummary;
import com.knowimran.rivoquery.interfaceapi.generated.model.ConnectorOptions;
import com.knowimran.rivoquery.interfaceapi.generated.model.CredentialState;
import com.knowimran.rivoquery.interfaceapi.generated.model.DatabaseKind;
import com.knowimran.rivoquery.interfaceapi.generated.model.EffectivePermission;
import com.knowimran.rivoquery.interfaceapi.generated.model.EndpointView;
import com.knowimran.rivoquery.interfaceapi.generated.model.ObjectAccessPolicy;
import com.knowimran.rivoquery.interfaceapi.generated.model.TlsMode;
import com.knowimran.rivoquery.interfaceapi.generated.model.TlsSummary;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;

/** Maps the domain {@link DatabaseConnection} (+ its {@link ConnectionProfile}) to safe REST DTOs. */
public final class ConnectionDtoMapper {

    private ConnectionDtoMapper() {}

    public static ConnectionSummary toSummary(DatabaseConnection connection, ConnectionProfile profile, ActorContext actor) {
        return new ConnectionSummary()
                .connectionId(connection.id().value())
                .name(profile.name())
                .kind(toKind(connection.kind()))
                .state(toState(connection.state()))
                .version(connection.version().value())
                .effectivePermissions(effectivePermissions(actor))
                .createdAt(toOffsetDateTime(profile.createdAt()))
                .updatedAt(toOffsetDateTime(profile.updatedAt()))
                .links(links(connection, actor));
    }

    public static ConnectionDetail toDetail(DatabaseConnection connection, ConnectionProfile profile, ActorContext actor) {
        ConnectionDetail detail = new ConnectionDetail()
                .connectionId(connection.id().value())
                .name(profile.name())
                .kind(toKind(connection.kind()))
                .state(toState(connection.state()))
                .version(connection.version().value())
                .effectivePermissions(effectivePermissions(actor))
                .createdAt(toOffsetDateTime(profile.createdAt()))
                .updatedAt(toOffsetDateTime(profile.updatedAt()))
                .links(links(connection, actor))
                .endpoint(new EndpointView(
                        profile.endpoint().host(), profile.endpoint().port(), profile.endpoint().databaseName()))
                .tls(new TlsSummary(
                        TlsMode.valueOf(profile.tlsMode().name()),
                        profile.customCaConfigured(),
                        profile.clientCertificateConfigured()))
                .connectorOptions(new ConnectorOptions().tcpKeepAlive(profile.tcpKeepAlive()))
                .objectPolicy(new ObjectAccessPolicy(
                        profile.objectPolicy().allowedSchemas(), profile.objectPolicy().allowedTables()))
                .limits(new ConnectionLimits(profile.limits().statementTimeoutMs(), profile.limits().maxRows()))
                .credentialState(credentialState(connection))
                .secretConfigured(connection.currentSecret().isPresent());
        connection.secretRetireAt().ifPresent(instant -> detail.setCredentialRetireAt(instant.atOffset(java.time.ZoneOffset.UTC)));
        return detail;
    }

    private static CredentialState credentialState(DatabaseConnection connection) {
        return switch (connection.state()) {
            case FAILED_VALIDATION -> CredentialState.RETAINED_FOR_RETEST;
            case DELETING, DELETED -> CredentialState.EXPIRED;
            default -> connection.currentSecret().isPresent() ? CredentialState.CURRENT : CredentialState.EXPIRED;
        };
    }

    private static Set<EffectivePermission> effectivePermissions(ActorContext actor) {
        if (actor.role() == WorkspaceRole.ADMINISTRATOR) {
            return new LinkedHashSet<>(Set.of(
                    EffectivePermission.VIEW_SCHEMA, EffectivePermission.QUERY,
                    EffectivePermission.EXPORT, EffectivePermission.MANAGE));
        }
        // Members reach this mapper only after ConnectionQueryService already
        // proved at least one effective grant exists; the exact grant set
        // isn't threaded through here (out of this task's minimal scope) so
        // members conservatively see an empty set here rather than an
        // over-claimed one. See docs/IMPLEMENTATION_TASKS.md P2-130 evidence.
        return Set.of();
    }

    private static ConnectionLinks links(DatabaseConnection connection, ActorContext actor) {
        var links = new ConnectionLinks().self("/api/v1/connections/" + connection.id().value());
        boolean manage = actor.role() == WorkspaceRole.ADMINISTRATOR;
        if (!manage) {
            return links;
        }
        links.setPermissions(links.getSelf() + "/permissions");
        if (!connection.state().name().equals("DELETING") && !connection.state().name().equals("DELETED")) {
            links.setTest(links.getSelf() + "/test");
            links.setRotateSecret(links.getSelf() + "/rotate-secret");
            links.setDisable(links.getSelf() + "/disable");
            links.setEnable(links.getSelf() + "/enable");
        }
        return links;
    }

    /**
     * S3 fix: real, persisted {@code connection_profiles} timestamps
     * (threaded through {@link ConnectionProfile}), not {@code
     * OffsetDateTime.now()} fabricated at response-serialization time.
     */
    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static DatabaseKind toKind(com.knowimran.rivoquery.connection.DatabaseKind kind) {
        return DatabaseKind.valueOf(kind.name());
    }

    private static ConnectionState toState(com.knowimran.rivoquery.connection.ConnectionState state) {
        return ConnectionState.valueOf(state.name());
    }
}
