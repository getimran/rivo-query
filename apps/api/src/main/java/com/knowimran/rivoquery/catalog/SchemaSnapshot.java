package com.knowimran.rivoquery.catalog;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record SchemaSnapshot(
    String snapshotId,
    WorkspaceId workspaceId,
    ConnectionId connectionId,
    SchemaFingerprint fingerprint,
    Instant discoveredAt,
    int objectCount,
    List<SchemaObject> objects
) {
    private static final int MAX_OBJECTS = 5000;

    public SchemaSnapshot {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        if (objects == null) {
            objects = Collections.emptyList();
        }
        List<SchemaObject> normalized = new ArrayList<>(objects);
        if (normalized.size() > MAX_OBJECTS) {
            throw new IllegalArgumentException("objects exceed maximum of " + MAX_OBJECTS);
        }
        objects = Collections.unmodifiableList(normalized);
        if (objectCount != objects.size()) {
            throw new IllegalArgumentException("objectCount must equal objects size");
        }
    }
}
