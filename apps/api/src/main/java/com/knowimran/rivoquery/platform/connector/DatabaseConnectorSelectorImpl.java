package com.knowimran.rivoquery.platform.connector;

import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.connector.DatabaseConnector;
import com.knowimran.rivoquery.connection.connector.DatabaseConnectorSelector;
import java.util.Map;
import java.util.Objects;

/**
 * Selects between the reviewed P2-110/P2-120 physical connectors by {@link
 * DatabaseKind}. Lives outside {@code platform.connector.postgresql}/{@code
 * platform.connector.mysql} (both off-limits to modify per this task's own
 * instructions) and only ever calls their already-public constructors/API.
 */
public final class DatabaseConnectorSelectorImpl implements DatabaseConnectorSelector {

    private final Map<DatabaseKind, DatabaseConnector> connectorsByKind;

    public DatabaseConnectorSelectorImpl(Map<DatabaseKind, DatabaseConnector> connectorsByKind) {
        this.connectorsByKind = Map.copyOf(Objects.requireNonNull(connectorsByKind, "connectorsByKind"));
    }

    @Override
    public DatabaseConnector select(DatabaseKind kind) {
        DatabaseConnector connector = connectorsByKind.get(Objects.requireNonNull(kind, "kind"));
        if (connector == null) {
            throw new IllegalStateException("no connector wired for " + kind);
        }
        return connector;
    }
}
