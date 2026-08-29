package com.knowimran.rivoquery.catalog.application;

import com.knowimran.rivoquery.catalog.SchemaRefreshOperation;
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

    List<SchemaRefreshOperation> findPending(String connectionId, Instant before);

    int resetExpiredRunning(Instant now);
}
