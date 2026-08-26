package com.knowimran.rivoquery.connection.application;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Optional;

/** See {@link ConnectionProfile} for why this port exists. */
public interface ConnectionProfileRepository {
    Optional<ConnectionProfile> find(WorkspaceId workspaceId, ConnectionId connectionId);

    void insert(ConnectionProfile profile);

    /** Upserts; used by PATCH updates. */
    void save(ConnectionProfile profile);

    void delete(WorkspaceId workspaceId, ConnectionId connectionId);
}
