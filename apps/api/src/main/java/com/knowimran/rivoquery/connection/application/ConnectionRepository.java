package com.knowimran.rivoquery.connection.application;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Optional;

public interface ConnectionRepository {
    Optional<DatabaseConnection> findByWorkspaceAndId(WorkspaceId workspaceId, ConnectionId connectionId);

    void insert(DatabaseConnection connection);

    boolean compareAndSet(DatabaseConnection connection, ConnectionVersion expectedVersion);
}
