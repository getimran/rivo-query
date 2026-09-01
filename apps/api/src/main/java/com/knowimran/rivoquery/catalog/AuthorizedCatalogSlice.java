package com.knowimran.rivoquery.catalog;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.List;
import java.util.Objects;

public record AuthorizedCatalogSlice(
        WorkspaceId workspaceId,
        ConnectionId connectionId,
        String snapshotFingerprint,
        List<SchemaObject> objects,
        int maxRows,
        String dialect,
        List<String> allowedFunctions) {

    public AuthorizedCatalogSlice {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(snapshotFingerprint, "snapshotFingerprint");
        Objects.requireNonNull(objects, "objects");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(allowedFunctions, "allowedFunctions");
        if (snapshotFingerprint.isBlank()) {
            throw new IllegalArgumentException("snapshotFingerprint must not be blank");
        }
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        if (!dialect.equals("postgresql") && !dialect.equals("mysql")) {
            throw new IllegalArgumentException("unsupported dialect: " + dialect);
        }
    }
}
