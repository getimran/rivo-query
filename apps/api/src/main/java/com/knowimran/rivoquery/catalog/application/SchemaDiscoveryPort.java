package com.knowimran.rivoquery.catalog.application;

import com.knowimran.rivoquery.catalog.SchemaObject;
import com.knowimran.rivoquery.catalog.SchemaSnapshot;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.DatabaseKind;
import java.util.List;

public interface SchemaDiscoveryPort {
    DatabaseKind kind();

    SchemaSnapshot discover(ConnectionId connectionId, DiscoveryPolicy policy) throws DiscoveryException;

    record DiscoveryPolicy(
        List<String> allowedSchemas,
        List<String> allowedTables,
        boolean includeSystemObjects,
        boolean includeDeprecated,
        int maxColumnsPerObject
    ) {
        private static final int MAX_COLUMNS = 200;

        public DiscoveryPolicy {
            if (allowedSchemas == null || allowedSchemas.isEmpty()) {
                throw new IllegalArgumentException("allowedSchemas must not be empty");
            }
            if (allowedTables == null) {
                allowedTables = List.of();
            }
            if (maxColumnsPerObject < 1 || maxColumnsPerObject > MAX_COLUMNS) {
                throw new IllegalArgumentException("maxColumnsPerObject must be between 1 and " + MAX_COLUMNS);
            }
        }
    }

    class DiscoveryException extends Exception {
        public DiscoveryException(String message) {
            super(message);
        }

        public DiscoveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
