package com.knowimran.rivoquery.catalog.application;

import com.knowimran.rivoquery.catalog.SchemaRefreshOperation;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SchemaRefreshOperationRepository {
    void insert(SchemaRefreshOperation operation);

    Optional<SchemaRefreshOperation> findById(String operationId);

    int updateState(String operationId, String state);

    int claim(String operationId, String claimedBy, Instant leaseExpiresAt, long expectedFencingToken, long newFencingToken);

    int renewLease(String operationId, Instant newLeaseExpiresAt);

    int retry(String operationId, int newAttempt, int newRetryBackoffMs, long expectedFencingToken);

    List<SchemaRefreshOperation> findPending(WorkspaceId workspaceId, String connectionId, Instant before);

    int resetExpiredRunning(Instant now);
}
