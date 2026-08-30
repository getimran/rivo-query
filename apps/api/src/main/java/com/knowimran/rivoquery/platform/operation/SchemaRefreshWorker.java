package com.knowimran.rivoquery.platform.operation;

import com.knowimran.rivoquery.catalog.application.SchemaRefreshOperationRepository;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshWorkerPort;
import com.knowimran.rivoquery.catalog.SchemaRefreshOperation;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public class SchemaRefreshWorker implements SchemaRefreshWorkerPort {
    private static final int DEFAULT_LEASE_SECONDS = 30;
    private static final int MAX_BACKOFF_SECONDS = 60;

    private final SchemaRefreshOperationRepository operationRepository;
    private final Clock clock;
    private final String workerId;

    public SchemaRefreshWorker(
            SchemaRefreshOperationRepository operationRepository,
            Clock clock,
            String workerId) {
        this.operationRepository = Objects.requireNonNull(operationRepository, "operationRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
    }

    public void recoverExpiredLeases() {
        operationRepository.resetExpiredRunning(Instant.now(clock));
    }

    public boolean claim(String operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return operationRepository.findById(operationId)
                .filter(operation -> "PENDING".equals(operation.state()))
                .map(operation -> {
                    long currentToken = operation.fencingToken();
                    long newToken = currentToken + 1;
                    Instant leaseExpiresAt = Instant.now(clock).plusSeconds(DEFAULT_LEASE_SECONDS);
                    int updated = operationRepository.claim(
                            operationId,
                            workerId,
                            leaseExpiresAt,
                            currentToken,
                            newToken
                    );
                    return updated == 1;
                })
                .orElse(false);
    }

    public void complete(String operationId) {
        Objects.requireNonNull(operationId, "operationId");
        operationRepository.updateState(operationId, "SUCCEEDED");
    }

    public void fail(String operationId) {
        Objects.requireNonNull(operationId, "operationId");
        operationRepository.updateState(operationId, "FAILED");
    }

    public void retry(String operationId) {
        Objects.requireNonNull(operationId, "operationId");
        operationRepository.findById(operationId)
                .ifPresent(operation -> {
                    int attempt = operation.attempt() + 1;
                    int backoff = Math.min(operation.retryBackoffMs() * 2, MAX_BACKOFF_SECONDS * 1000);
                    operationRepository.retry(operationId, attempt, backoff, operation.fencingToken());
                });
    }

    public void release(String operationId) {
        Objects.requireNonNull(operationId, "operationId");
        operationRepository.findById(operationId)
                .filter(operation -> workerId.equals(operation.claimedBy()))
                .ifPresent(operation -> {
                    int attempt = operation.attempt();
                    int backoff = Math.min(operation.retryBackoffMs() * 2, MAX_BACKOFF_SECONDS * 1000);
                    operationRepository.retry(operationId, attempt, backoff, operation.fencingToken());
                });
    }

    public List<SchemaRefreshOperation> findPending(String connectionId) {
        Objects.requireNonNull(connectionId, "connectionId");
        return operationRepository.findPending(connectionId, Instant.now(clock));
    }
}
