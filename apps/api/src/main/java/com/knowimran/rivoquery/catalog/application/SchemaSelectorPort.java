package com.knowimran.rivoquery.catalog.application;

import com.knowimran.rivoquery.catalog.SchemaObject;
import com.knowimran.rivoquery.catalog.SchemaObjectPage;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.List;

public interface SchemaSelectorPort {
    SchemaObjectPage selectRelevantObjects(
        WorkspaceId workspaceId,
        ConnectionId connectionId,
        String question,
        List<SchemaObject> candidates,
        SelectionPolicy policy
    );

    record SelectionPolicy(
        int maxTables,
        int maxColumnsPerTable,
        int maxTotalColumns,
        int maxHops,
        int maxTokens
    ) {
        public SelectionPolicy {
            if (maxTables < 1) {
                throw new IllegalArgumentException("maxTables must be at least 1");
            }
            if (maxColumnsPerTable < 1) {
                throw new IllegalArgumentException("maxColumnsPerTable must be at least 1");
            }
            if (maxTotalColumns < 1) {
                throw new IllegalArgumentException("maxTotalColumns must be at least 1");
            }
            if (maxHops < 0) {
                throw new IllegalArgumentException("maxHops must not be negative");
            }
            if (maxTokens < 1) {
                throw new IllegalArgumentException("maxTokens must be at least 1");
            }
        }
    }
}
