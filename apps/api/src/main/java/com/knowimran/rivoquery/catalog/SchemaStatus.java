package com.knowimran.rivoquery.catalog;

import com.knowimran.rivoquery.connection.ConnectionId;
import java.time.Instant;
import java.util.Objects;

public record SchemaStatus(
    ConnectionId connectionId,
    String currentSnapshotId,
    Instant discoveredAt,
    int objectCount,
    boolean stale
) {
    public SchemaStatus {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(currentSnapshotId, "currentSnapshotId");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        if (currentSnapshotId.isBlank()) {
            throw new IllegalArgumentException("currentSnapshotId must not be blank");
        }
        if (objectCount < 0) {
            throw new IllegalArgumentException("objectCount must not be negative");
        }
    }
}
