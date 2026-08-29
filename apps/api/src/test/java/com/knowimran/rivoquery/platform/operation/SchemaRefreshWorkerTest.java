package com.knowimran.rivoquery.platform.operation;

import com.knowimran.rivoquery.catalog.SchemaRefreshOperation;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshOperationRepository;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaRefreshWorkerTest {
    @Test
    void claimSucceedsForPendingOperation() {
        FakeRepository repo = new FakeRepository();
        repo.insert(new SchemaRefreshOperation(
                "op_1",
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                "PENDING",
                null,
                null,
                null,
                0,
                1,
                3,
                5000,
                Instant.now(),
                Instant.now()
        ));
        SchemaRefreshWorker worker = new SchemaRefreshWorker(repo, Instant::now, "worker-1");
        boolean claimed = worker.claim("op_1");
        assertThat(claimed).isTrue();
        assertThat(repo.operations.get(0).state()).isEqualTo("RUNNING");
        assertThat(repo.operations.get(0).claimedBy()).isEqualTo("worker-1");
        assertThat(repo.operations.get(0).fencingToken()).isEqualTo(1);
    }

    @Test
    void claimFailsForNonPendingOperation() {
        FakeRepository repo = new FakeRepository();
        repo.insert(new SchemaRefreshOperation(
                "op_1",
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                "RUNNING",
                "worker-1",
                Instant.now(),
                Instant.now().plusSeconds(30),
                1,
                1,
                3,
                5000,
                Instant.now(),
                Instant.now()
        ));
        SchemaRefreshWorker worker = new SchemaRefreshWorker(repo, Instant::now, "worker-1");
        boolean claimed = worker.claim("op_1");
        assertThat(claimed).isFalse();
    }

    @Test
    void completeSetsSucceeded() {
        FakeRepository repo = new FakeRepository();
        repo.insert(new SchemaRefreshOperation(
                "op_1",
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                "RUNNING",
                "worker-1",
                Instant.now(),
                Instant.now().plusSeconds(30),
                1,
                1,
                3,
                5000,
                Instant.now(),
                Instant.now()
        ));
        SchemaRefreshWorker worker = new SchemaRefreshWorker(repo, Instant::now, "worker-1");
        worker.complete("op_1");
        assertThat(repo.operations.get(0).state()).isEqualTo("SUCCEEDED");
    }

    @Test
    void failSetsFailedAndRetriesWhenAttemptsRemain() {
        FakeRepository repo = new FakeRepository();
        repo.insert(new SchemaRefreshOperation(
                "op_1",
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                "RUNNING",
                "worker-1",
                Instant.now(),
                Instant.now().plusSeconds(30),
                1,
                1,
                3,
                5000,
                Instant.now(),
                Instant.now()
        ));
        SchemaRefreshWorker worker = new SchemaRefreshWorker(repo, Instant::now, "worker-1");
        worker.fail("op_1");
        assertThat(repo.operations.get(0).state()).isEqualTo("FAILED");
        worker.retry("op_1");
        assertThat(repo.operations.get(0).state()).isEqualTo("PENDING");
        assertThat(repo.operations.get(0).attempt()).isEqualTo(2);
        assertThat(repo.operations.get(0).retryBackoffMs()).isEqualTo(10000);
    }

    @Test
    void releaseReturnsToPendingForClaimedWorker() {
        FakeRepository repo = new FakeRepository();
        repo.insert(new SchemaRefreshOperation(
                "op_1",
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                "RUNNING",
                "worker-1",
                Instant.now(),
                Instant.now().plusSeconds(30),
                1,
                1,
                3,
                5000,
                Instant.now(),
                Instant.now()
        ));
        SchemaRefreshWorker worker = new SchemaRefreshWorker(repo, Instant::now, "worker-1");
        worker.release("op_1");
        assertThat(repo.operations.get(0).state()).isEqualTo("PENDING");
    }

    @Test
    void recoverExpiredLeasesResetsRunningOperations() {
        FakeRepository repo = new FakeRepository();
        repo.insert(new SchemaRefreshOperation(
                "op_1",
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                "RUNNING",
                "worker-1",
                Instant.now().minusSeconds(60),
                Instant.now().minusSeconds(30),
                1,
                1,
                3,
                5000,
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(120)
        ));
        SchemaRefreshWorker worker = new SchemaRefreshWorker(repo, Instant::now, "worker-1");
        worker.recoverExpiredLeases();
        assertThat(repo.operations.get(0).state()).isEqualTo("PENDING");
    }

    @Test
    void findPendingReturnsPendingOperations() {
        FakeRepository repo = new FakeRepository();
        repo.insert(new SchemaRefreshOperation(
                "op_1",
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                "PENDING",
                null,
                null,
                null,
                0,
                1,
                3,
                5000,
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(120)
        ));
        SchemaRefreshWorker worker = new SchemaRefreshWorker(repo, Instant::now, "worker-1");
        List<SchemaRefreshOperation> pending = worker.findPending("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).operationId()).isEqualTo("op_1");
    }

    private static class FakeRepository implements SchemaRefreshOperationRepository {
        final List<SchemaRefreshOperation> operations = new ArrayList<>();

        @Override
        public void insert(SchemaRefreshOperation operation) {
            operations.add(operation);
        }

        @Override
        public Optional<SchemaRefreshOperation> findById(String operationId) {
            return operations.stream().filter(o -> o.operationId().equals(operationId)).findFirst();
        }

        @Override
        public int updateState(String operationId, String state) {
            for (int i = 0; i < operations.size(); i++) {
                SchemaRefreshOperation op = operations.get(i);
                if (op.operationId().equals(operationId)) {
                    operations.set(i, new SchemaRefreshOperation(
                            op.operationId(),
                            op.workspaceId(),
                            op.connectionId(),
                            state,
                            op.claimedBy(),
                            op.claimedAt(),
                            op.leaseExpiresAt(),
                            op.fencingToken(),
                            op.attempt(),
                            op.maxAttempts(),
                            op.retryBackoffMs(),
                            op.createdAt(),
                            Instant.now()
                    ));
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int claim(String operationId, String claimedBy, Instant leaseExpiresAt, long expectedFencingToken, long newFencingToken) {
            for (int i = 0; i < operations.size(); i++) {
                SchemaRefreshOperation op = operations.get(i);
                if (op.operationId().equals(operationId) && op.fencingToken() == expectedFencingToken) {
                    operations.set(i, new SchemaRefreshOperation(
                            op.operationId(),
                            op.workspaceId(),
                            op.connectionId(),
                            "RUNNING",
                            claimedBy,
                            Instant.now(),
                            leaseExpiresAt,
                            newFencingToken,
                            op.attempt() + 1,
                            op.maxAttempts(),
                            op.retryBackoffMs(),
                            op.createdAt(),
                            Instant.now()
                    ));
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int renewLease(String operationId, Instant newLeaseExpiresAt) {
            return 0;
        }

        @Override
        public int retry(String operationId, int newAttempt, int newRetryBackoffMs) {
            for (int i = 0; i < operations.size(); i++) {
                SchemaRefreshOperation op = operations.get(i);
                if (op.operationId().equals(operationId)) {
                    operations.set(i, new SchemaRefreshOperation(
                            op.operationId(),
                            op.workspaceId(),
                            op.connectionId(),
                            "PENDING",
                            null,
                            null,
                            null,
                            op.fencingToken() + 1,
                            newAttempt,
                            op.maxAttempts(),
                            newRetryBackoffMs,
                            op.createdAt(),
                            Instant.now()
                    ));
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public List<SchemaRefreshOperation> findPending(String connectionId, Instant before) {
            List<SchemaRefreshOperation> result = new ArrayList<>();
            for (SchemaRefreshOperation op : operations) {
                if (op.connectionId().value().equals(connectionId) && "PENDING".equals(op.state())) {
                    result.add(op);
                }
            }
            return result;
        }

        @Override
        public int resetExpiredRunning(Instant now) {
            int count = 0;
            for (int i = 0; i < operations.size(); i++) {
                SchemaRefreshOperation op = operations.get(i);
                if ("RUNNING".equals(op.state()) && op.leaseExpiresAt() != null && op.leaseExpiresAt().isBefore(now)) {
                    operations.set(i, new SchemaRefreshOperation(
                            op.operationId(),
                            op.workspaceId(),
                            op.connectionId(),
                            "PENDING",
                            null,
                            null,
                            null,
                            op.fencingToken(),
                            op.attempt(),
                            op.maxAttempts(),
                            op.retryBackoffMs(),
                            op.createdAt(),
                            Instant.now()
                    ));
                    count++;
                }
            }
            return count;
        }
    }
}
