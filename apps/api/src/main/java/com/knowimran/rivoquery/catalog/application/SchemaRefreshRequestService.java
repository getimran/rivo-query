package com.knowimran.rivoquery.catalog.application;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Objects;

public class SchemaRefreshRequestService {
    private final SchemaRefreshOrchestrator orchestrator;

    public SchemaRefreshRequestService(SchemaRefreshOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    public void refresh(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId) throws SchemaDiscoveryPort.DiscoveryException {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        actor.requireWorkspace(workspaceId);
        orchestrator.refreshSchema(actor, workspaceId, connectionId);
    }
}
