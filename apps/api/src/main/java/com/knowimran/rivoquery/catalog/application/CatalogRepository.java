package com.knowimran.rivoquery.catalog.application;

import com.knowimran.rivoquery.catalog.CatalogObjectId;
import com.knowimran.rivoquery.catalog.SchemaObjectPage;
import com.knowimran.rivoquery.catalog.SchemaSnapshot;
import com.knowimran.rivoquery.catalog.SchemaStatus;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Optional;

public interface CatalogRepository {
    Optional<SchemaSnapshot> findCurrentSnapshot(WorkspaceId workspaceId, ConnectionId connectionId);

    Optional<SchemaSnapshot> findSnapshot(WorkspaceId workspaceId, ConnectionId connectionId, CatalogObjectId snapshotId);

    void insert(SchemaSnapshot snapshot);

    void installCurrentSnapshot(WorkspaceId workspaceId, ConnectionId connectionId, SchemaSnapshot snapshot);

    SchemaStatus findStatus(WorkspaceId workspaceId, ConnectionId connectionId);

    SchemaObjectPage findObjects(WorkspaceId workspaceId, ConnectionId connectionId, String cursor, int limit);
}
