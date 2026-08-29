package com.knowimran.rivoquery.catalog.application;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

@FunctionalInterface
public interface SchemaRefreshConnectionFactory {
    Connection open(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId) throws SQLException;

    record Wrapped(Connection connection, SchemaRefreshConnectionFactory factory) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            factory.close(connection);
        }
    }

    default Wrapped openWrapped(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId) throws SQLException {
        Connection connection = open(actor, workspaceId, connectionId);
        return new Wrapped(connection, this);
    }

    default void close(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            // Best-effort close
        }
    }
}
