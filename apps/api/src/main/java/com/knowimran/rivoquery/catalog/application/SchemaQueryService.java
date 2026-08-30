package com.knowimran.rivoquery.catalog.application;

import com.knowimran.rivoquery.catalog.CatalogObjectId;
import com.knowimran.rivoquery.catalog.SchemaColumn;
import com.knowimran.rivoquery.catalog.SchemaObject;
import com.knowimran.rivoquery.catalog.SchemaObjectPage;
import com.knowimran.rivoquery.catalog.SchemaSnapshot;
import com.knowimran.rivoquery.catalog.SchemaStatus;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.ConnectionPermission;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Objects;
import java.util.Optional;

public class SchemaQueryService {
    private final CatalogRepository catalogRepository;

    public SchemaQueryService(CatalogRepository catalogRepository) {
        this.catalogRepository = Objects.requireNonNull(catalogRepository, "catalogRepository");
    }

    public SchemaStatus getStatus(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        actor.requireWorkspace(workspaceId);
        return catalogRepository.findStatus(workspaceId, connectionId);
    }

    public SchemaObjectPage listObjects(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId, String cursor, int limit) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(cursor, "cursor");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        actor.requireWorkspace(workspaceId);
        return catalogRepository.findObjects(workspaceId, connectionId, cursor, limit);
    }

    public Optional<SchemaSnapshot> currentSnapshot(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        actor.requireWorkspace(workspaceId);
        return catalogRepository.findCurrentSnapshot(workspaceId, connectionId);
    }
}
