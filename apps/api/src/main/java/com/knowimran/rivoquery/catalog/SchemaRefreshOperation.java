package com.knowimran.rivoquery.catalog;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Instant;
import java.util.Objects;

public record SchemaRefreshOperation(
    String operationId,
    WorkspaceId workspaceId,
    ConnectionId connectionId,
    String state,
    String claimedBy,
    Instant claimedAt,
    Instant leaseExpiresAt,
    long fencingToken,
    int attempt,
    int maxAttempts,
    int retryBackoffMs,
    Instant createdAt,
    Instant updatedAt
) {
    public SchemaRefreshOperation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must not be negative");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (retryBackoffMs < 0) {
            throw new IllegalArgumentException("retryBackoffMs must not be negative");
        }
    }
}
