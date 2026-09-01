package com.knowimran.rivoquery.interfaceapi.query;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.application.ConnectionPermissionRepository;
import com.knowimran.rivoquery.generation.GenerationRequest;
import com.knowimran.rivoquery.generation.application.GenerationService;
import com.knowimran.rivoquery.generation.application.GenerationService.GenerationResult;
import com.knowimran.rivoquery.generation.application.GenerationService.QueryPolicyViolationException;
import com.knowimran.rivoquery.connection.application.lifecycle.CanonicalIdGenerator;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.ConnectionPermission;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import com.knowimran.rivoquery.interfaceapi.generated.api.QueriesApi;
import com.knowimran.rivoquery.interfaceapi.generated.model.AuditEvent;
import com.knowimran.rivoquery.interfaceapi.generated.model.AuditEventPage;
import com.knowimran.rivoquery.interfaceapi.generated.model.QueryFeedbackRequest;
import com.knowimran.rivoquery.interfaceapi.generated.model.QueryPreviewRequest;
import com.knowimran.rivoquery.interfaceapi.generated.model.QueryPreviewResponse;
import com.knowimran.rivoquery.interfaceapi.generated.model.QueryPreviewResponsePolicy;
import com.knowimran.rivoquery.interfaceapi.generated.model.QueryPreviewStatus;
import com.knowimran.rivoquery.interfaceapi.support.ActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QueryController implements QueriesApi {

    private final ActorResolver actorResolver;
    private final GenerationService generationService;
    private final ConnectionPermissionRepository permissions;
    private final CanonicalIdGenerator idGenerator;
    private final ObjectProvider<HttpServletRequest> requestProvider;

    public QueryController(
            ActorResolver actorResolver,
            GenerationService generationService,
            ConnectionPermissionRepository permissions,
            CanonicalIdGenerator idGenerator,
            ObjectProvider<HttpServletRequest> requestProvider) {
        this.actorResolver = actorResolver;
        this.generationService = generationService;
        this.permissions = permissions;
        this.idGenerator = idGenerator;
        this.requestProvider = requestProvider;
    }

    private HttpServletRequest request() {
        return requestProvider.getObject();
    }

    @Override
    public ResponseEntity<QueryPreviewResponse> createQueryPreview(
            String xRivoQueryWorkspaceId,
            QueryPreviewRequest queryPreviewRequest,
            String xRequestId) {
        ActorContext actor = actorResolver.resolve(request());
        WorkspaceId workspaceId = new WorkspaceId(xRivoQueryWorkspaceId);
        ConnectionId connectionId = new ConnectionId(queryPreviewRequest.getConnectionId());
        requireQuery(actor, workspaceId, connectionId);

        String question = Objects.requireNonNull(queryPreviewRequest.getQuestion(), "question");

        GenerationResult result;
        try {
            result = generationService.ask(actor, workspaceId, connectionId, question);
        } catch (GenerationService.SchemaAccessDeniedException e) {
            throw new QueryAccessDeniedException();
        } catch (GenerationService.SchemaRefreshFailedException e) {
            throw new QuerySchemaStaleException();
        } catch (GenerationService.GenerationFailedException e) {
            throw new QueryProviderException();
        } catch (GenerationService.QueryPolicyViolationException e) {
            throw new com.knowimran.rivoquery.interfaceapi.query.QueryPolicyViolationException(e);
        }

        var response = result.response();
        var approved = result.approvedQuery();

        String requestId = approved != null
                 ? approved.queryId().value()
                 : idGenerator.newQueryRequestId().value();

        QueryPreviewResponse dto = new QueryPreviewResponse()
                .requestId(requestId)
                .status(toApiStatus(response.status()))
                .connectionId(connectionId.value());

        if (response.status() == com.knowimran.rivoquery.generation.AmbiguityStatus.READY) {
            var proposal = response.proposal();
            dto.sql(approved.canonicalSql())
                .sqlFingerprint(approved.sqlFingerprint())
                .explanation(proposal.explanation())
                .assumptions(proposal.assumptions())
                .referencedObjects(approved.referencedObjects())
                .warnings(proposal.warnings())
                .policy(buildPolicy(approved));
        } else if (response.status() == com.knowimran.rivoquery.generation.AmbiguityStatus.NEEDS_CLARIFICATION) {
            var proposal = response.proposal();
            dto.clarificationQuestion(proposal.clarificationQuestion())
                    .choices(List.of());
        }

        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .header("X-Request-Id", xRequestId != null ? xRequestId : generateRequestId())
                .body(dto);
    }

    @Override
    public ResponseEntity<Void> executeQuery(
            String xRivoQueryWorkspaceId,
            String requestId,
            String xRequestId) {
        ActorContext actor = actorResolver.resolve(request());
        WorkspaceId workspaceId = new WorkspaceId(xRivoQueryWorkspaceId);
        throw new QueryExecutionNotImplementedException();
    }

    @Override
    public ResponseEntity<Void> submitQueryFeedback(
            String xRivoQueryWorkspaceId,
            String requestId,
            QueryFeedbackRequest queryFeedbackRequest,
            String xRequestId) {
        ActorContext actor = actorResolver.resolve(request());
        WorkspaceId workspaceId = new WorkspaceId(xRivoQueryWorkspaceId);
        throw new QueryFeedbackNotImplementedException();
    }

    @Override
    public ResponseEntity<AuditEventPage> listAuditEvents(
            String xRivoQueryWorkspaceId,
            String xRequestId,
            String cursor,
            Integer limit) {
        ActorContext actor = actorResolver.resolve(request());
        WorkspaceId workspaceId = new WorkspaceId(xRivoQueryWorkspaceId);
        actor.requireAdministrator();
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .header("X-Request-Id", xRequestId != null ? xRequestId : generateRequestId())
                .body(new AuditEventPage().items(List.of()));
    }

    private void requireQuery(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId) {
        if (!hasPermission(actor, workspaceId, connectionId, ConnectionPermission.QUERY)) {
            throw new QueryAccessDeniedException();
        }
    }

    private boolean hasPermission(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId,
                                  ConnectionPermission required) {
        if (actor.role() == WorkspaceRole.ADMINISTRATOR) {
            return true;
        }
        return permissions.find(workspaceId, connectionId, actor.membershipId())
                .map(grant -> com.knowimran.rivoquery.identity.PermissionMatrix.allows(
                        actor, workspaceId, actor.membershipId(), grant.permissions(), required))
                .orElse(false);
    }

    private QueryPreviewStatus toApiStatus(com.knowimran.rivoquery.generation.AmbiguityStatus status) {
        return switch (status) {
            case READY -> QueryPreviewStatus.READY;
            case NEEDS_CLARIFICATION -> QueryPreviewStatus.NEEDS_CLARIFICATION;
            case UNANSWERABLE -> QueryPreviewStatus.UNANSWERABLE;
        };
    }

    private QueryPreviewResponsePolicy buildPolicy(com.knowimran.rivoquery.querypolicy.ApprovedQuery approved) {
        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) approved.enforcedLimits();
        QueryPreviewResponsePolicy policy = new QueryPreviewResponsePolicy()
                .version(approved.policyVersion())
                .maxRows(toInteger(limits.get("maxRows")))
                .statementTimeoutMs(toInteger(limits.getOrDefault("statementTimeoutMs", 15000)));
        if (limits.containsKey("maxTables")) {
            policy.setMaxTables(toInteger(limits.get("maxTables")));
        }
        if (limits.containsKey("maxColumns")) {
            policy.setMaxColumns(toInteger(limits.get("maxColumns")));
        }
        if (limits.containsKey("maxJoins")) {
            policy.setMaxJoins(toInteger(limits.get("maxJoins")));
        }
        if (limits.containsKey("maxCteLevels")) {
            policy.setMaxCteLevels(toInteger(limits.get("maxCteLevels")));
        }
        return policy;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    private String generateRequestId() {
        return "req-" + java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
