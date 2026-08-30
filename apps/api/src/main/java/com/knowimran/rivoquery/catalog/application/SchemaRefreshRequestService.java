package com.knowimran.rivoquery.catalog.application;

import com.knowimran.rivoquery.catalog.application.SchemaRefreshFailedException;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Objects;

public class SchemaRefreshRequestService {
    private final SchemaRefreshOrchestrator orchestrator;

    public SchemaRefreshRequestService(SchemaRefreshOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    public String refresh(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        actor.requireWorkspace(workspaceId);
        try {
            return orchestrator.refreshSchema(actor, workspaceId, connectionId);
        } catch (SchemaDiscoveryPort.DiscoveryException e) {
            throw new SchemaRefreshFailedException("Schema refresh failed", e);
        }
    }
}
