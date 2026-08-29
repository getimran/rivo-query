package com.knowimran.rivoquery.interfaceapi.schema;

import com.knowimran.rivoquery.catalog.SchemaObjectPage;
import com.knowimran.rivoquery.catalog.SchemaRefreshOperation;
import com.knowimran.rivoquery.catalog.application.SchemaQueryService;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshOperationRepository;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshRequestService;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.application.ConnectionPermissionRepository;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.ConnectionPermission;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import com.knowimran.rivoquery.interfaceapi.generated.api.SchemaApi;
import com.knowimran.rivoquery.interfaceapi.generated.model.Operation;
import com.knowimran.rivoquery.interfaceapi.generated.model.OperationAction;
import com.knowimran.rivoquery.interfaceapi.generated.model.OperationLinks;
import com.knowimran.rivoquery.interfaceapi.generated.model.OperationState;
import com.knowimran.rivoquery.interfaceapi.generated.model.SchemaPage;
import com.knowimran.rivoquery.interfaceapi.generated.model.SchemaStatus;
import com.knowimran.rivoquery.interfaceapi.support.ActorResolver;
import com.knowimran.rivoquery.interfaceapi.support.IdempotencyGuard;
import com.knowimran.rivoquery.interfaceapi.support.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SchemaController implements SchemaApi {

    private final ActorResolver actorResolver;
    private final SchemaQueryService queryService;
    private final SchemaRefreshRequestService refreshService;
    private final SchemaRefreshOperationRepository refreshOperations;
    private final ConnectionPermissionRepository permissions;
    private final IdempotencyGuard idempotency;
    private final ObjectProvider<HttpServletRequest> requestProvider;

    public SchemaController(
            ActorResolver actorResolver,
            SchemaQueryService queryService,
            SchemaRefreshRequestService refreshService,
            SchemaRefreshOperationRepository refreshOperations,
            ConnectionPermissionRepository permissions,
            IdempotencyGuard idempotency,
            ObjectProvider<HttpServletRequest> requestProvider) {
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver");
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.refreshService = Objects.requireNonNull(refreshService, "refreshService");
        this.refreshOperations = Objects.requireNonNull(refreshOperations, "refreshOperations");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.requestProvider = Objects.requireNonNull(requestProvider, "requestProvider");
    }

    private HttpServletRequest request() {
        return requestProvider.getObject();
    }

    @Override
    public ResponseEntity<SchemaStatus> getSchemaStatus(
            String xRivoQueryWorkspaceId, String connectionId, String xRequestId) {
        ActorContext actor = actorResolver.resolve(request());
        WorkspaceId workspaceId = new WorkspaceId(xRivoQueryWorkspaceId);
        ConnectionId connId = new ConnectionId(connectionId);
        requireViewSchema(actor, workspaceId, connId);
        com.knowimran.rivoquery.catalog.SchemaStatus status = queryService.getStatus(actor, workspaceId, connId);
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .header("X-Request-Id", xRequestId != null ? xRequestId : generateRequestId())
                .body(SchemaDtoMapper.toStatus(status));
    }

    @Override
    public ResponseEntity<SchemaPage> listSchemaObjects(
            String xRivoQueryWorkspaceId, String connectionId, String xRequestId, String cursor, Integer limit) {
        ActorContext actor = actorResolver.resolve(request());
        WorkspaceId workspaceId = new WorkspaceId(xRivoQueryWorkspaceId);
        ConnectionId connId = new ConnectionId(connectionId);
        requireViewSchema(actor, workspaceId, connId);
        String safeCursor = cursor != null ? cursor : "0";
        int pageSize = limit != null ? Math.min(limit, 100) : 50;
        SchemaObjectPage page = queryService.listObjects(actor, workspaceId, connId, safeCursor, pageSize);
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .header("X-Request-Id", xRequestId != null ? xRequestId : generateRequestId())
                .body(SchemaDtoMapper.toPage(page));
    }

    @Override
    public ResponseEntity<Operation> refreshSchema(
            String xRivoQueryWorkspaceId, String connectionId, String idempotencyKey, String xRequestId) {
        ActorContext actor = actorResolver.resolve(request());
        actor.requireAdministrator();
        WorkspaceId workspaceId = new WorkspaceId(xRivoQueryWorkspaceId);
        ConnectionId connId = new ConnectionId(connectionId);

        String fingerprint = RequestFingerprint.of(connectionId, idempotencyKey);
        Optional<com.knowimran.rivoquery.interfaceapi.support.IdempotencyRecord> reservation =
                idempotency.reserve(actor.workspaceId(), actor.actorId(), idempotencyKey, "refreshSchema", fingerprint);
        if (reservation.isPresent()) {
            com.knowimran.rivoquery.interfaceapi.support.IdempotencyRecord record = reservation.get();
            if (record.operationId().isPresent()) {
                SchemaRefreshOperation refreshOp = refreshOperations.findById(record.operationId().get())
                        .orElseThrow(() -> new SchemaRefreshOperationNotFoundException());
                return ResponseEntity.accepted()
                        .header("Location", "/api/v1/connections/" + connectionId + "/schema/operations/" + record.operationId().get())
                        .header("X-Request-Id", xRequestId != null ? xRequestId : generateRequestId())
                        .body(toOperation(refreshOp));
            }
            throw new SchemaRefreshInFlightException();
        }

        try {
            refreshService.refresh(actor, workspaceId, connId);
        } catch (Exception e) {
            idempotency.rollback(actor.workspaceId(), actor.actorId(), idempotencyKey);
            throw e;
        }

        SchemaRefreshOperation created = refreshOperations.findPending(connId.value(), OffsetDateTime.now().toInstant())
                .stream().findFirst()
                .orElseThrow(() -> new SchemaRefreshOperationNotFoundException());

        idempotency.complete(actor.workspaceId(), actor.actorId(), idempotencyKey, Optional.of(created.operationId()));

        return ResponseEntity.accepted()
                .header("Location", "/api/v1/connections/" + connectionId + "/schema/operations/" + created.operationId())
                .header("X-Request-Id", xRequestId != null ? xRequestId : generateRequestId())
                .body(toOperation(created));
    }

    private void requireViewSchema(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId) {
        if (!hasPermission(actor, workspaceId, connectionId, ConnectionPermission.VIEW_SCHEMA)) {
            throw new SchemaAccessDeniedException();
        }
    }

    private boolean hasPermission(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId, ConnectionPermission required) {
        if (actor.role() == WorkspaceRole.ADMINISTRATOR) {
            return true;
        }
        return permissions.find(workspaceId, connectionId, actor.membershipId())
                .map(grant -> com.knowimran.rivoquery.identity.PermissionMatrix.allows(
                        actor, workspaceId, actor.membershipId(), grant.permissions(), required))
                .orElse(false);
    }

    private Operation toOperation(SchemaRefreshOperation operation) {
        OperationLinks links = new OperationLinks()
                .self("/api/v1/connections/" + operation.workspaceId().value() + "/schema/operations/" + operation.operationId())
                .connection("/api/v1/connections/" + operation.connectionId().value());
        Operation dto = new Operation(
                operation.operationId(),
                OperationAction.SCHEMA_REFRESH,
                OperationState.valueOf(operation.state()),
                OffsetDateTime.ofInstant(operation.createdAt(), java.time.ZoneId.systemDefault()),
                OffsetDateTime.ofInstant(operation.updatedAt(), java.time.ZoneId.systemDefault()),
                links
        );
        dto.setConnectionId(operation.connectionId().value());
        return dto;
    }

    private String generateRequestId() {
        return "req-" + java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
