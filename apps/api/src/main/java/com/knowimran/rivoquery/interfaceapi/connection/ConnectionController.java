package com.knowimran.rivoquery.interfaceapi.connection;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.AuditRequestId;
import com.knowimran.rivoquery.connection.application.ConnectionLimits;
import com.knowimran.rivoquery.connection.application.ConnectionObjectPolicy;
import com.knowimran.rivoquery.connection.application.ConnectionProfile;
import com.knowimran.rivoquery.connection.application.ConnectionProfileRepository;
import com.knowimran.rivoquery.connection.application.ConnectionTlsMode;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.DurableOperationRepository;
import com.knowimran.rivoquery.connection.application.destination.StructuredEndpoint;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionLifecycleResult;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionLifecycleService;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionQueryService;
import com.knowimran.rivoquery.connection.application.lifecycle.IndeterminateLifecycleOutcomeException;
import com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.interfaceapi.RequestIdFilter;
import com.knowimran.rivoquery.interfaceapi.generated.api.ConnectionsApi;
import com.knowimran.rivoquery.interfaceapi.generated.model.ConnectionDetail;
import com.knowimran.rivoquery.interfaceapi.generated.model.ConnectionPage;
import com.knowimran.rivoquery.interfaceapi.generated.model.ConnectionSummary;
import com.knowimran.rivoquery.interfaceapi.generated.model.CreateConnectionRequest;
import com.knowimran.rivoquery.interfaceapi.generated.model.Operation;
import com.knowimran.rivoquery.interfaceapi.generated.model.RotateSecretRequest;
import com.knowimran.rivoquery.interfaceapi.generated.model.UpdateConnectionRequest;
import com.knowimran.rivoquery.interfaceapi.operation.OperationDtoMapper;
import com.knowimran.rivoquery.interfaceapi.support.ActorResolver;
import com.knowimran.rivoquery.interfaceapi.support.EtagCodec;
import com.knowimran.rivoquery.interfaceapi.support.IdempotencyGuard;
import com.knowimran.rivoquery.interfaceapi.support.RequestFingerprint;
import com.knowimran.rivoquery.interfaceapi.support.TlsCertificateKeyPairValidator;
import com.knowimran.rivoquery.interfaceapi.support.ValidationFailedException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wraps {@link ConnectionLifecycleService}/{@link ConnectionQueryService}.
 * Every method's first line is {@code actorResolver.resolve(request())}
 * (auth-before-load, PD2-003); resource lookup never happens before that.
 *
 * <p>{@code enableConnection} is implemented as a stable "not yet supported"
 * failure - see {@link ConnectionEnableNotSupportedException}. {@code
 * ConnectionProfile} handling (name/endpoint/tls/connectorOptions/objectPolicy/limits)
 * is a P2-130 addition documented on {@link ConnectionProfile} itself.
 *
 * <p>Every mutating handler follows the same reserve-before-mutate
 * idempotency protocol (post-review race-safety fix - see {@link
 * IdempotencyGuard}'s own Javadoc): {@code idempotency.reserve(...)} before
 * the mutation is attempted, then {@link #guardMutation} runs the mutation
 * and either lets a successful result flow to {@code idempotency.complete}
 * or rolls the reservation back on a definite, no-side-effect failure -
 * never on {@link IndeterminateLifecycleOutcomeException}, which by
 * definition means a real effect may already have occurred and must not be
 * retried automatically.
 */
@RestController
public class ConnectionController implements ConnectionsApi {

    private static final Duration MUTATION_DEADLINE = Duration.ofSeconds(30);

    private final ActorResolver actorResolver;
    private final ConnectionLifecycleService lifecycle;
    private final ConnectionQueryService queries;
    private final ConnectionProfileRepository profiles;
    private final DurableOperationRepository operations;
    private final IdempotencyGuard idempotency;
    private final ObjectProvider<HttpServletRequest> requestProvider;
    private final Clock clock;

    /**
     * Constructor-injects {@link ObjectProvider} rather than {@link
     * HttpServletRequest} directly - see {@code OperationController}'s
     * identical Javadoc for why (a real request-scoped proxy only exists in
     * a servlet {@code WebApplicationContext}; deferring the lookup keeps
     * this bean constructible in a {@code WebApplicationType.NONE} test
     * context that never actually invokes a handler method).
     */
    public ConnectionController(
            ActorResolver actorResolver,
            ConnectionLifecycleService lifecycle,
            ConnectionQueryService queries,
            ConnectionProfileRepository profiles,
            DurableOperationRepository operations,
            IdempotencyGuard idempotency,
            ObjectProvider<HttpServletRequest> requestProvider,
            Clock clock) {
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.requestProvider = Objects.requireNonNull(requestProvider, "requestProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private HttpServletRequest request() {
        return requestProvider.getObject();
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Override
    public ResponseEntity<ConnectionDetail> createConnection(
            String xRivoQueryWorkspaceId, String idempotencyKey, CreateConnectionRequest body, String xRequestId) {
        ActorContext actor = actorResolver.resolve(request());
        actor.requireAdministrator();
        TlsCertificateKeyPairValidator.requirePaired(
                body.getTls().getClientCertificatePem(), body.getTls().getClientPrivateKeyPem());
        StructuredEndpoint endpoint = toEndpoint(body.getEndpoint());
        DatabaseKind kind = DatabaseKind.valueOf(body.getKind().name());

        String fingerprint = RequestFingerprint.of(body);
        var reservation = idempotency.reserve(actor.workspaceId(), actor.actorId(), idempotencyKey, "createConnection", fingerprint);
        if (reservation.isPresent()) {
            return replayedCreation(actor, reservation.get());
        }

        ConnectionLifecycleResult result = guardMutation(actor, idempotencyKey, () -> {
            var command = new ConnectionLifecycleService.CreateConnectionCommand(
                    actor, kind, endpoint, currentRequestId(), deadline());
            try (DestructibleSecretMaterial material = toSecretMaterial(body.getSecret())) {
                return lifecycle.create(command, material);
            }
        });

        java.time.Instant now = clock.instant();
        ConnectionProfile profile = new ConnectionProfile(
                actor.workspaceId(), result.connection().id(), body.getName(), endpoint,
                ConnectionTlsMode.valueOf(body.getTls().getMode().name()),
                body.getTls().getCaCertificatePem() != null,
                body.getTls().getClientCertificatePem() != null,
                body.getConnectorOptions().getTcpKeepAlive() == null || body.getConnectorOptions().getTcpKeepAlive(),
                toObjectPolicy(body.getObjectPolicy()),
                toLimits(body.getLimits()),
                now, now);
        ConnectionProfile responseProfile = profile;
        try {
            profiles.insert(profile);
        } catch (RuntimeException profileFailure) {
            // Best-effort: see ConnectionProfile's class Javadoc - this table
            // is deliberately outside ConnectionLifecycleService's own
            // transaction/audit boundary, and the connection itself is
            // already durably ACTIVE at this point. The row was never
            // written, so a future GET degrades to
            // ConnectionProfile.placeholder(...) - this response mirrors
            // that same degraded view rather than optimistically returning
            // data that was never actually persisted (S8 fix).
            responseProfile = ConnectionProfile.placeholder(actor.workspaceId(), result.connection().id());
        }

        idempotency.complete(actor.workspaceId(), actor.actorId(), idempotencyKey,
                result.operation().map(op -> op.id().value()));

        ConnectionDetail dto = ConnectionDtoMapper.toDetail(result.connection(), responseProfile, actor);
        return ResponseEntity.created(java.net.URI.create("/api/v1/connections/" + result.connection().id().value()))
                .eTag(EtagCodec.format(result.connection().version()))
                .body(dto);
    }

    // ------------------------------------------------------------------
    // list / get
    // ------------------------------------------------------------------

    @Override
    public ResponseEntity<ConnectionPage> listConnections(
            String xRivoQueryWorkspaceId, String xRequestId, String cursor, Integer limit) {
        ActorContext actor = actorResolver.resolve(request());
        Optional<ConnectionId> after = parseCursor(cursor);
        int pageSize = limit == null ? 50 : limit;
        List<DatabaseConnection> page = queries.list(actor, after, pageSize);
        List<ConnectionSummary> items = page.stream()
                .map(connection -> ConnectionDtoMapper.toSummary(connection, profileOrPlaceholder(actor, connection.id()), actor))
                .toList();
        ConnectionPage body = new ConnectionPage().items(items);
        if (items.size() == pageSize && !page.isEmpty()) {
            body.setNextCursor(page.get(page.size() - 1).id().value());
        }
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<ConnectionDetail> getConnection(
            String xRivoQueryWorkspaceId, String connectionIdValue, String xRequestId) {
        ActorContext actor = actorResolver.resolve(request());
        ConnectionId connectionId = toConnectionId(connectionIdValue);
        DatabaseConnection connection = queries.get(actor, connectionId);
        ConnectionDetail dto = ConnectionDtoMapper.toDetail(connection, profileOrPlaceholder(actor, connectionId), actor);
        return ResponseEntity.ok().eTag(EtagCodec.format(connection.version())).body(dto);
    }

    // ------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------

    @Override
    public ResponseEntity<ConnectionDetail> updateConnection(
            String xRivoQueryWorkspaceId, String idempotencyKey, String ifMatch, String connectionIdValue,
            UpdateConnectionRequest body, String xRequestId) {
        ActorContext actor = actorResolver.resolve(request());
        actor.requireAdministrator();
        UpdateConnectionRequestValidator.rejectEndpoint(body);
        UpdateConnectionRequestValidator.requireNonEmpty(body);
        ConnectionId connectionId = toConnectionId(connectionIdValue);
        ConnectionVersion expectedVersion = parseIfMatch(ifMatch);

        String fingerprint = RequestFingerprint.of(connectionIdValue, body);
        var reservation = idempotency.reserve(actor.workspaceId(), actor.actorId(), idempotencyKey, "updateConnection", fingerprint);
        if (reservation.isPresent()) {
            DatabaseConnection current = queries.get(actor, connectionId);
            return ResponseEntity.ok().eTag(EtagCodec.format(current.version()))
                    .body(ConnectionDtoMapper.toDetail(current, profileOrPlaceholder(actor, connectionId), actor));
        }

        ConnectionLifecycleResult result = guardMutation(actor, idempotencyKey, () -> {
            var command = new ConnectionLifecycleService.UpdateConnectionCommand(
                    actor, connectionId, expectedVersion, currentRequestId(), deadline());
            return lifecycle.update(command);
        });

        ConnectionProfile existing = profileOrPlaceholder(actor, connectionId);
        ConnectionProfile merged = mergeProfile(existing, body).withUpdatedAt(clock.instant());
        ConnectionProfile responseProfile = merged;
        try {
            profiles.save(merged);
        } catch (RuntimeException profileFailure) {
            // Best-effort: see the identical comment in createConnection and
            // ConnectionProfile's own class Javadoc. Unlike create, a
            // profile row may already have existed here - a failed upsert
            // very likely left it unchanged, so the honest degraded view is
            // whatever GET already showed before this request, not the
            // optimistically-merged value nor a fabricated placeholder (S8
            // fix).
            responseProfile = existing;
        }

        idempotency.complete(actor.workspaceId(), actor.actorId(), idempotencyKey, Optional.empty());

        return ResponseEntity.ok().eTag(EtagCodec.format(result.connection().version()))
                .body(ConnectionDtoMapper.toDetail(result.connection(), responseProfile, actor));
    }

    // ------------------------------------------------------------------
    // disable / enable
    // ------------------------------------------------------------------

    @Override
    public ResponseEntity<ConnectionDetail> disableConnection(
            String connectionIdValue, String xRivoQueryWorkspaceId, String idempotencyKey, String ifMatch, String xRequestId) {
        ActorContext actor = actorResolver.resolve(request());
        actor.requireAdministrator();
        ConnectionId connectionId = toConnectionId(connectionIdValue);
        ConnectionVersion expectedVersion = parseIfMatch(ifMatch);

        String fingerprint = RequestFingerprint.of(connectionIdValue, ifMatch);
        var reservation = idempotency.reserve(actor.workspaceId(), actor.actorId(), idempotencyKey, "disableConnection", fingerprint);
        if (reservation.isPresent()) {
            DatabaseConnection current = queries.get(actor, connectionId);
            return ResponseEntity.ok().eTag(EtagCodec.format(current.version()))
                    .body(ConnectionDtoMapper.toDetail(current, profileOrPlaceholder(actor, connectionId), actor));
        }

        ConnectionLifecycleResult result = guardMutation(actor, idempotencyKey, () -> {
            var command = new ConnectionLifecycleService.DisableConnectionCommand(
                    actor, connectionId, expectedVersion, currentRequestId(), deadline());
            return lifecycle.disable(command);
        });

        idempotency.complete(actor.workspaceId(), actor.actorId(), idempotencyKey, Optional.empty());

        return ResponseEntity.ok().eTag(EtagCodec.format(result.connection().version()))
                .body(ConnectionDtoMapper.toDetail(result.connection(), profileOrPlaceholder(actor, connectionId), actor));
    }

    @Override
    public ResponseEntity<ConnectionDetail> enableConnection(
            String connectionIdValue, String xRivoQueryWorkspaceId, String idempotencyKey, String ifMatch, String xRequestId) {
        ActorContext actor = actorResolver.resolve(request());
        actor.requireAdministrator();
        ConnectionId connectionId = toConnectionId(connectionIdValue);
        DatabaseConnection connection = queries.get(actor, connectionId);
        connection.requireVersion(parseIfMatch(ifMatch));
        throw new ConnectionEnableNotSupportedException();
    }

    // ------------------------------------------------------------------
    // test / rotate-secret (structurally blocked - see ConnectionLifecycleService)
    // ------------------------------------------------------------------

    @Override
    public ResponseEntity<Operation> testConnection(
            String connectionIdValue, String xRivoQueryWorkspaceId, String idempotencyKey, String ifMatch, String xRequestId) {
        ActorContext actor = actorResolver.resolve(request());
        actor.requireAdministrator();
        ConnectionId connectionId = toConnectionId(connectionIdValue);
        ConnectionVersion expectedVersion = parseIfMatch(ifMatch);

        String fingerprint = RequestFingerprint.of(connectionIdValue, ifMatch);
        var reservation = idempotency.reserve(actor.workspaceId(), actor.actorId(), idempotencyKey, "testConnection", fingerprint);
        if (reservation.isPresent() && reservation.get().operationId().isPresent()) {
            return ResponseEntity.accepted().body(operationOrThrow(actor, reservation.get().operationId().orElseThrow()));
        }

        ConnectionLifecycleResult result = guardMutation(actor, idempotencyKey, () -> {
            var command = new ConnectionLifecycleService.TestConnectionCommand(
                    actor, connectionId, expectedVersion, currentRequestId(), deadline());
            return lifecycle.test(command);
        });
        idempotency.complete(actor.workspaceId(), actor.actorId(), idempotencyKey,
                result.operation().map(op -> op.id().value()));
        // Unreachable today (lifecycle.test always throws
        // ConnectionEndpointNotYetPersistedException); kept honest rather
        // than removed, ready for whichever task lands a real endpoint.
        return ResponseEntity.accepted().body(OperationDtoMapper.toDto(result.operation().orElseThrow()));
    }

    @Override
    public ResponseEntity<Operation> rotateConnectionSecret(
            String connectionIdValue, String xRivoQueryWorkspaceId, String idempotencyKey, String ifMatch,
            RotateSecretRequest body, String xRequestId) {
        ActorContext actor = actorResolver.resolve(request());
        actor.requireAdministrator();
        TlsCertificateKeyPairValidator.requirePaired(body.getClientCertificatePem(), body.getClientPrivateKeyPem());
        ConnectionId connectionId = toConnectionId(connectionIdValue);
        ConnectionVersion expectedVersion = parseIfMatch(ifMatch);

        String fingerprint = RequestFingerprint.of(connectionIdValue, ifMatch, body);
        var reservation = idempotency.reserve(actor.workspaceId(), actor.actorId(), idempotencyKey, "rotateConnectionSecret", fingerprint);
        if (reservation.isPresent() && reservation.get().operationId().isPresent()) {
            return ResponseEntity.accepted().body(operationOrThrow(actor, reservation.get().operationId().orElseThrow()));
        }

        ConnectionLifecycleResult result = guardMutation(actor, idempotencyKey, () -> {
            var command = new ConnectionLifecycleService.RotateSecretCommand(
                    actor, connectionId, expectedVersion, currentRequestId(), deadline());
            try (DestructibleSecretMaterial material = toSecretMaterial(body.getSecret())) {
                return lifecycle.rotateSecret(command, material);
            }
        });
        idempotency.complete(actor.workspaceId(), actor.actorId(), idempotencyKey,
                result.operation().map(op -> op.id().value()));
        // Unreachable today; see testConnection.
        return ResponseEntity.accepted().body(OperationDtoMapper.toDto(result.operation().orElseThrow()));
    }

    // ------------------------------------------------------------------
    // delete
    // ------------------------------------------------------------------

    @Override
    public ResponseEntity<Operation> deleteConnection(
            String xRivoQueryWorkspaceId, String idempotencyKey, String ifMatch, String connectionIdValue, String xRequestId) {
        ActorContext actor = actorResolver.resolve(request());
        actor.requireAdministrator();
        ConnectionId connectionId = toConnectionId(connectionIdValue);
        ConnectionVersion expectedVersion = parseIfMatch(ifMatch);

        String fingerprint = RequestFingerprint.of(connectionIdValue, ifMatch);
        var reservation = idempotency.reserve(actor.workspaceId(), actor.actorId(), idempotencyKey, "deleteConnection", fingerprint);
        if (reservation.isPresent() && reservation.get().operationId().isPresent()) {
            return ResponseEntity.accepted().body(operationOrThrow(actor, reservation.get().operationId().orElseThrow()));
        }

        ConnectionLifecycleResult result = guardMutation(actor, idempotencyKey, () -> {
            var command = new ConnectionLifecycleService.DeleteConnectionCommand(
                    actor, connectionId, expectedVersion, currentRequestId(), deadline());
            return lifecycle.delete(command);
        });

        try {
            profiles.delete(actor.workspaceId(), connectionId);
        } catch (RuntimeException ignoredCleanupFailure) {
            // Best-effort: the connection is already durably tombstoned;
            // a stray profile row for a DELETED connection is inert.
        }

        idempotency.complete(actor.workspaceId(), actor.actorId(), idempotencyKey,
                result.operation().map(op -> op.id().value()));

        Operation operationDto = OperationDtoMapper.toDto(result.operation().orElseThrow());
        return ResponseEntity.accepted()
                .location(java.net.URI.create(operationDto.getLinks().getSelf()))
                .body(operationDto);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Runs {@code mutation} and reconciles its outcome with the idempotency
     * reservation {@code idempotencyKey} already holds (see {@link
     * IdempotencyGuard}'s own Javadoc for the full protocol): a definite,
     * no-side-effect failure rolls the reservation back so a legitimate
     * retry is never mistaken for a duplicate; {@link
     * IndeterminateLifecycleOutcomeException} - the one failure shape that
     * means a real effect may already have occurred - is deliberately
     * re-thrown without rolling back, since a retry after that must not risk
     * duplicating whatever already happened. The caller is still responsible
     * for calling {@code idempotency.complete(...)} on the success path.
     */
    private <T> T guardMutation(ActorContext actor, String idempotencyKey, Supplier<T> mutation) {
        try {
            return mutation.get();
        } catch (IndeterminateLifecycleOutcomeException indeterminate) {
            throw indeterminate;
        } catch (RuntimeException noEffectFailure) {
            idempotency.rollback(actor.workspaceId(), actor.actorId(), idempotencyKey);
            throw noEffectFailure;
        }
    }

    private Operation operationOrThrow(ActorContext actor, String operationIdValue) {
        var id = new com.knowimran.rivoquery.connection.OperationId(operationIdValue);
        var operation = operations.findByWorkspaceAndId(actor.workspaceId(), id)
                .orElseThrow(() -> new ValidationFailedException("reconcilable operation is unavailable"));
        return OperationDtoMapper.toDto(operation);
    }

    /**
     * {@code createConnection} is fully synchronous ({@link
     * ConnectionLifecycleService#create} never returns a pending/running
     * result - see its own Javadoc), so unlike test/rotate-secret/delete
     * (which the OpenAPI contract genuinely models as async 202-Accepted
     * {@code Operation} bodies) an exact idempotent replay of {@code create}
     * has no operation-shaped result to hand back: the generated {@code
     * ConnectionsApi.createConnection} signature is {@code
     * ResponseEntity<ConnectionDetail>} (the OpenAPI generator resolves the
     * operation's single Java return type from its 201 response schema; the
     * 202 alternative exists on paper but is unreachable from this method's
     * own return type). The correct replay is therefore the same {@code
     * ConnectionDetail} the original call already produced, re-derived from
     * the durable operation's own {@code connectionId} rather than
     * re-running any part of {@code create}.
     */
    private ResponseEntity<ConnectionDetail> replayedCreation(ActorContext actor, com.knowimran.rivoquery.connection.application.IdempotencyRecord record) {
        ConnectionId createdId = record.operationId()
                .map(operationIdValue -> new com.knowimran.rivoquery.connection.OperationId(operationIdValue))
                .flatMap(operationId -> operations.findByWorkspaceAndId(actor.workspaceId(), operationId))
                .flatMap(com.knowimran.rivoquery.connection.application.DurableOperation::connectionId)
                .orElseThrow(() -> new ValidationFailedException("reconcilable operation is unavailable"));
        DatabaseConnection existing = queries.get(actor, createdId);
        ConnectionDetail dto = ConnectionDtoMapper.toDetail(existing, profileOrPlaceholder(actor, createdId), actor);
        return ResponseEntity.created(java.net.URI.create("/api/v1/connections/" + createdId.value()))
                .eTag(EtagCodec.format(existing.version()))
                .body(dto);
    }

    private ConnectionProfile profileOrPlaceholder(ActorContext actor, ConnectionId connectionId) {
        return profiles.find(actor.workspaceId(), connectionId)
                .orElseGet(() -> ConnectionProfile.placeholder(actor.workspaceId(), connectionId));
    }

    private ConnectionProfile mergeProfile(ConnectionProfile existing, UpdateConnectionRequest body) {
        ConnectionProfile merged = existing;
        if (body.getName() != null) {
            merged = merged.withName(body.getName());
        }
        // body.getEndpoint() is rejected earlier by
        // UpdateConnectionRequestValidator.rejectEndpoint - see B1 evidence
        // in docs/IMPLEMENTATION_TASKS.md P2-130 for why an endpoint update
        // is not merged/persisted here.
        if (body.getTlsMode() != null) {
            merged = merged.withTlsMode(ConnectionTlsMode.valueOf(body.getTlsMode().name()));
        }
        if (body.getConnectorOptions() != null) {
            Boolean tcpKeepAlive = body.getConnectorOptions().getTcpKeepAlive();
            merged = merged.withTcpKeepAlive(tcpKeepAlive == null || tcpKeepAlive);
        }
        if (body.getObjectPolicy() != null) {
            merged = merged.withObjectPolicy(toObjectPolicy(body.getObjectPolicy()));
        }
        if (body.getLimits() != null) {
            merged = merged.withLimits(toLimits(body.getLimits()));
        }
        return merged;
    }

    private static ConnectionId toConnectionId(String value) {
        try {
            return new ConnectionId(value);
        } catch (IllegalArgumentException malformed) {
            throw new com.knowimran.rivoquery.connection.application.lifecycle.ConnectionNotFoundException();
        }
    }

    private static Optional<ConnectionId> parseCursor(String cursor) {
        if (cursor == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ConnectionId(cursor));
        } catch (IllegalArgumentException malformed) {
            throw new ValidationFailedException("cursor is not a valid connection identifier");
        }
    }

    private static ConnectionVersion parseIfMatch(String ifMatch) {
        try {
            return EtagCodec.parse(ifMatch);
        } catch (IllegalArgumentException malformed) {
            throw new ValidationFailedException("If-Match is not a well-formed version token");
        }
    }

    private static StructuredEndpoint toEndpoint(com.knowimran.rivoquery.interfaceapi.generated.model.EndpointInput input) {
        try {
            return new StructuredEndpoint(input.getHost(), input.getPort(), input.getDatabase());
        } catch (IllegalArgumentException malformed) {
            throw new ValidationFailedException("endpoint is not valid");
        }
    }

    private static ConnectionObjectPolicy toObjectPolicy(com.knowimran.rivoquery.interfaceapi.generated.model.ObjectAccessPolicy policy) {
        try {
            return new ConnectionObjectPolicy(policy.getAllowedSchemas(), policy.getAllowedTables());
        } catch (IllegalArgumentException malformed) {
            throw new ValidationFailedException("objectPolicy is not valid");
        }
    }

    private static ConnectionLimits toLimits(com.knowimran.rivoquery.interfaceapi.generated.model.ConnectionLimits limits) {
        try {
            return new ConnectionLimits(limits.getStatementTimeoutMs(), limits.getMaxRows());
        } catch (IllegalArgumentException malformed) {
            throw new ValidationFailedException("limits are not valid");
        }
    }

    private static DestructibleSecretMaterial toSecretMaterial(com.knowimran.rivoquery.interfaceapi.generated.model.SecretInput secret) {
        byte[] username = secret.getUsername().getBytes(StandardCharsets.UTF_8);
        byte[] password = secret.getPassword().getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[username.length + 1 + password.length];
        System.arraycopy(username, 0, combined, 0, username.length);
        combined[username.length] = 0;
        System.arraycopy(password, 0, combined, username.length + 1, password.length);
        try {
            return new DestructibleSecretMaterial(combined);
        } finally {
            java.util.Arrays.fill(combined, (byte) 0);
            java.util.Arrays.fill(username, (byte) 0);
            java.util.Arrays.fill(password, (byte) 0);
        }
    }

    private AuditRequestId currentRequestId() {
        String requestId = (String) request().getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return new AuditRequestId(requestId);
    }

    private Deadline deadline() {
        return new Deadline(clock.instant().plus(MUTATION_DEADLINE));
    }
}
