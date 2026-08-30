package com.knowimran.rivoquery.generation.application;

import com.knowimran.rivoquery.catalog.AuthorizedCatalogSlice;
import com.knowimran.rivoquery.catalog.CanonicalQualifiedName;
import com.knowimran.rivoquery.catalog.SchemaColumn;
import com.knowimran.rivoquery.catalog.SchemaObject;
import com.knowimran.rivoquery.catalog.SchemaSnapshot;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionPermissionGrant;
import com.knowimran.rivoquery.connection.application.ConnectionPermissionRepository;
import com.knowimran.rivoquery.generation.GenerationRequest;
import com.knowimran.rivoquery.generation.GenerationResponse;
import com.knowimran.rivoquery.generation.SqlGenerationModel;
import com.knowimran.rivoquery.generation.SqlProposal;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.GrantablePermission;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.querypolicy.QuerySentry;
import com.knowimran.rivoquery.querypolicy.ApprovedQuery;
import com.knowimran.rivoquery.catalog.application.SchemaQueryService;
import com.knowimran.rivoquery.catalog.application.SchemaSelectorService;
import com.knowimran.rivoquery.catalog.application.SchemaSelectorPort;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class GenerationService {

    private final SqlGenerationModel model;
    private final SchemaSelectorService schemaSelector;
    private final SchemaQueryService schemaQuery;
    private final QuerySentry querySentry;
    private final ConnectionPermissionRepository permissionRepository;
    private final String policyVersion;

    public GenerationService(
            SqlGenerationModel model,
            SchemaSelectorService schemaSelector,
            SchemaQueryService schemaQuery,
            QuerySentry querySentry,
            ConnectionPermissionRepository permissionRepository,
            String policyVersion) {
        this.model = Objects.requireNonNull(model, "model");
        this.schemaSelector = Objects.requireNonNull(schemaSelector, "schemaSelector");
        this.schemaQuery = Objects.requireNonNull(schemaQuery, "schemaQuery");
        this.querySentry = Objects.requireNonNull(querySentry, "querySentry");
        this.permissionRepository = Objects.requireNonNull(permissionRepository, "permissionRepository");
        this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
    }

    public GenerationResult ask(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId, String question) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(question, "question");
        actor.requireWorkspace(workspaceId);

        MembershipId membershipId = actor.membershipId();
        Optional<ConnectionPermissionGrant> grantOpt = permissionRepository.find(workspaceId, connectionId, membershipId);
        if (grantOpt.isEmpty() || grantOpt.get().permissions().stream().noneMatch(p -> p == GrantablePermission.QUERY)) {
            throw new SchemaAccessDeniedException();
        }

        Optional<SchemaSnapshot> snapshotOpt = schemaQuery.currentSnapshot(actor, workspaceId, connectionId);
        if (snapshotOpt.isEmpty()) {
            throw new SchemaRefreshFailedException("no schema snapshot available");
        }
        SchemaSnapshot snapshot = snapshotOpt.get();

        var candidates = snapshot.objects();
        var selectionPolicy = new SchemaSelectorPort.SelectionPolicy(
                10, 20, 100, 3, 2000
        );
        var selected = schemaSelector.selectRelevantObjects(
                workspaceId, connectionId, question, candidates, selectionPolicy
        );

        var catalogSlice = new AuthorizedCatalogSlice(
                workspaceId,
                connectionId,
                snapshot.fingerprint().value(),
                selected.items(),
                200,
                "postgresql",
                List.of()
        );

        var request = new GenerationRequest(
                workspaceId,
                connectionId,
                question,
                catalogSlice,
                List.of("select-only", "read-only"),
                "v1",
                new GenerationRequest.GenerationOptions(
                        200,
                        "postgresql",
                        List.of(),
                        true
                )
        );

        GenerationResponse response;
        try {
            response = model.generate(request);
        } catch (SqlGenerationModel.GenerationException e) {
            throw new GenerationFailedException("generation failed", e);
        }

        if (response.status() != com.knowimran.rivoquery.generation.AmbiguityStatus.READY) {
            return new GenerationResult(response, null);
        }

        SqlProposal proposal = response.proposal();
        var proposedQuery = new com.knowimran.rivoquery.querypolicy.ProposedQuery(
                proposal.sql(),
                com.knowimran.rivoquery.querypolicy.ProposedQuery.AmbiguityStatus.valueOf(proposal.ambiguityStatus().name()),
                proposal.confidence(),
                proposal.warnings()
        );
        try {
            ApprovedQuery approved = querySentry.evaluate(
                    proposedQuery,
                    catalogSlice,
                    com.knowimran.rivoquery.connection.DatabaseKind.POSTGRESQL,
                    new QuerySentry.QueryPolicy(
                            10, 20, 5, 5, 200, 500, 300
                    )
            );
            return new GenerationResult(response, approved);
        } catch (QuerySentry.QueryPolicyException e) {
            throw new QueryPolicyViolationException("query policy violation", e);
        }
    }

    public record GenerationResult(
            GenerationResponse response,
            ApprovedQuery approvedQuery) {
    }

    public static class SchemaAccessDeniedException extends RuntimeException {
        public SchemaAccessDeniedException() {
            super("schema access denied");
        }
    }

    public static class SchemaRefreshFailedException extends RuntimeException {
        public SchemaRefreshFailedException(String message) {
            super(message);
        }
    }

    public static class GenerationFailedException extends RuntimeException {
        public GenerationFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class QueryPolicyViolationException extends RuntimeException {
        public QueryPolicyViolationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
