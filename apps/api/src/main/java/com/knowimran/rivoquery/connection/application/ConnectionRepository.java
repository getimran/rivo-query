package com.knowimran.rivoquery.connection.application;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.List;
import java.util.Optional;

public interface ConnectionRepository {
    Optional<DatabaseConnection> findByWorkspaceAndId(WorkspaceId workspaceId, ConnectionId connectionId);

    void insert(DatabaseConnection connection);

    boolean compareAndSet(DatabaseConnection connection, ConnectionVersion expectedVersion);

    /**
     * Returns up to {@code limit} publicly visible connections (see
     * {@link com.knowimran.rivoquery.connection.ConnectionState#isPublicState()})
     * in {@code workspaceId}, ordered ascending by {@code connectionId},
     * strictly after {@code afterConnectionId} when present. Added for
     * P2-100's list use case; {@code PENDING_SECRET}/{@code DELETED}
     * connections are never returned, matching every other read path's
     * treatment of those states as internal-only.
     */
    List<DatabaseConnection> findPage(WorkspaceId workspaceId, Optional<ConnectionId> afterConnectionId, int limit);
}
