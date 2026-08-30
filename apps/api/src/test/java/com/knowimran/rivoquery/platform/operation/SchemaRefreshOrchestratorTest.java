package com.knowimran.rivoquery.platform.operation;

import com.knowimran.rivoquery.catalog.SchemaRefreshOperation;
import com.knowimran.rivoquery.catalog.SchemaSnapshot;
import com.knowimran.rivoquery.catalog.application.CatalogRepository;
import com.knowimran.rivoquery.catalog.application.SchemaDiscoveryPort;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshConnectionFactory;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshOperationRepository;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshWorkerPort;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshOrchestrator;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshService;
import com.knowimran.rivoquery.catalog.application.SchemaDiscoveryPort.DiscoveryException;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.CurrentMembership;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.MembershipStatus;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import com.knowimran.rivoquery.identity.application.SingleMembershipRepository;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaRefreshOrchestratorTest {
    private static ActorContext actor() {
        var membership = new CurrentMembership(
                new ActorId("act_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new MembershipId("mem_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                WorkspaceRole.ADMINISTRATOR,
                MembershipStatus.ACTIVE);
        return new ActorContext.Authority(new SingleMembershipRepository(membership))
                .resolve("issuer", "subject", new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"));
    }

    @Test
    void refreshSchemaOrchestratesClaimAndCompletion() throws DiscoveryException {
        FakeOperationRepository opRepo = new FakeOperationRepository();
        CatalogRepository catalogRepository = new CatalogRepository() {
            @Override
            public Optional<SchemaSnapshot> findCurrentSnapshot(WorkspaceId workspaceId, ConnectionId connectionId) {
                return Optional.empty();
            }

            @Override
            public Optional<SchemaSnapshot> findSnapshot(WorkspaceId workspaceId, ConnectionId connectionId, com.knowimran.rivoquery.catalog.CatalogObjectId snapshotId) {
                return Optional.empty();
            }

            @Override
            public void insert(com.knowimran.rivoquery.catalog.SchemaSnapshot snapshot) {
            }

            @Override
            public void installCurrentSnapshot(WorkspaceId workspaceId, ConnectionId connectionId, com.knowimran.rivoquery.catalog.SchemaSnapshot snapshot) {
            }

            @Override
            public com.knowimran.rivoquery.catalog.SchemaStatus findStatus(WorkspaceId workspaceId, ConnectionId connectionId) {
                return null;
            }

            @Override
            public com.knowimran.rivoquery.catalog.SchemaObjectPage findObjects(WorkspaceId workspaceId, ConnectionId connectionId, String cursor, int limit) {
                return null;
            }
        };
        SchemaDiscoveryPort discoveryPort = new SchemaDiscoveryPort() {
            @Override
            public com.knowimran.rivoquery.connection.DatabaseKind kind() {
                return null;
            }

            @Override
            public SchemaSnapshot discover(Connection connection, WorkspaceId workspaceId, ConnectionId connectionId, SchemaDiscoveryPort.DiscoveryPolicy policy) throws SchemaDiscoveryPort.DiscoveryException {
                return null;
            }
        };
        com.knowimran.rivoquery.connection.application.lifecycle.CanonicalIdGenerator idGenerator = new com.knowimran.rivoquery.connection.application.lifecycle.CanonicalIdGenerator() {
            @Override
            public com.knowimran.rivoquery.connection.ConnectionId newConnectionId() {
                return null;
            }

            @Override
            public com.knowimran.rivoquery.connection.OperationId newOperationId() {
                return null;
            }

            @Override
            public String newSnapshotId() {
                return null;
            }
        };
        FakeRefreshService refreshService = new FakeRefreshService(catalogRepository, discoveryPort, idGenerator, false);
        SchemaRefreshWorkerPort worker = new SchemaRefreshWorkerPort() {
            @Override
            public boolean claim(String operationId) {
                return opRepo.findById(operationId)
                        .filter(operation -> "PENDING".equals(operation.state()))
                        .map(operation -> {
                            long currentToken = operation.fencingToken();
                            long newToken = currentToken + 1;
                            Instant leaseExpiresAt = Instant.now(Clock.systemUTC()).plusSeconds(30);
                            int updated = opRepo.claim(operationId, "worker-1", leaseExpiresAt, currentToken, newToken);
                            return updated == 1;
                        })
                        .orElse(false);
            }

            @Override
            public void complete(String operationId) {
                opRepo.updateState(operationId, "SUCCEEDED");
            }

            @Override
            public void fail(String operationId) {
                opRepo.updateState(operationId, "FAILED");
            }
        };
        SchemaRefreshConnectionFactory connectionFactory = new SchemaRefreshConnectionFactory() {
            @Override
            public Connection open(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId) throws SQLException {
                return new NoOpConnection();
            }
        };
        SchemaRefreshOrchestrator orchestrator = new SchemaRefreshOrchestrator(
                worker, refreshService, catalogRepository, opRepo, connectionFactory, Clock.systemUTC()
        );
        orchestrator.refreshSchema(
                actor(),
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX")
        );
        assertThat(opRepo.operations).hasSize(1);
        assertThat(opRepo.operations.get(0).state()).isEqualTo("SUCCEEDED");
    }

    @Test
    void refreshSchemaMarksFailedWhenServiceThrows() {
        FakeOperationRepository opRepo = new FakeOperationRepository();
        CatalogRepository catalogRepository = new CatalogRepository() {
            @Override
            public Optional<SchemaSnapshot> findCurrentSnapshot(WorkspaceId workspaceId, ConnectionId connectionId) {
                return Optional.empty();
            }

            @Override
            public Optional<SchemaSnapshot> findSnapshot(WorkspaceId workspaceId, ConnectionId connectionId, com.knowimran.rivoquery.catalog.CatalogObjectId snapshotId) {
                return Optional.empty();
            }

            @Override
            public void insert(com.knowimran.rivoquery.catalog.SchemaSnapshot snapshot) {
            }

            @Override
            public void installCurrentSnapshot(WorkspaceId workspaceId, ConnectionId connectionId, com.knowimran.rivoquery.catalog.SchemaSnapshot snapshot) {
            }

            @Override
            public com.knowimran.rivoquery.catalog.SchemaStatus findStatus(WorkspaceId workspaceId, ConnectionId connectionId) {
                return null;
            }

            @Override
            public com.knowimran.rivoquery.catalog.SchemaObjectPage findObjects(WorkspaceId workspaceId, ConnectionId connectionId, String cursor, int limit) {
                return null;
            }
        };
        SchemaDiscoveryPort discoveryPort = new SchemaDiscoveryPort() {
            @Override
            public com.knowimran.rivoquery.connection.DatabaseKind kind() {
                return null;
            }

            @Override
            public SchemaSnapshot discover(Connection connection, WorkspaceId workspaceId, ConnectionId connectionId, SchemaDiscoveryPort.DiscoveryPolicy policy) throws SchemaDiscoveryPort.DiscoveryException {
                throw new SchemaDiscoveryPort.DiscoveryException("simulated failure");
            }
        };
        com.knowimran.rivoquery.connection.application.lifecycle.CanonicalIdGenerator idGenerator = new com.knowimran.rivoquery.connection.application.lifecycle.CanonicalIdGenerator() {
            @Override
            public com.knowimran.rivoquery.connection.ConnectionId newConnectionId() {
                return null;
            }

            @Override
            public com.knowimran.rivoquery.connection.OperationId newOperationId() {
                return null;
            }

            @Override
            public String newSnapshotId() {
                return null;
            }
        };
        FakeRefreshService refreshService = new FakeRefreshService(catalogRepository, discoveryPort, idGenerator, true);
        SchemaRefreshWorkerPort worker = new SchemaRefreshWorkerPort() {
            @Override
            public boolean claim(String operationId) {
                return opRepo.findById(operationId)
                        .filter(operation -> "PENDING".equals(operation.state()))
                        .map(operation -> {
                            long currentToken = operation.fencingToken();
                            long newToken = currentToken + 1;
                            Instant leaseExpiresAt = Instant.now(Clock.systemUTC()).plusSeconds(30);
                            int updated = opRepo.claim(operationId, "worker-1", leaseExpiresAt, currentToken, newToken);
                            return updated == 1;
                        })
                        .orElse(false);
            }

            @Override
            public void complete(String operationId) {
                opRepo.updateState(operationId, "SUCCEEDED");
            }

            @Override
            public void fail(String operationId) {
                opRepo.updateState(operationId, "FAILED");
            }
        };
        SchemaRefreshConnectionFactory connectionFactory = new SchemaRefreshConnectionFactory() {
            @Override
            public Connection open(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId) throws SQLException {
                return new NoOpConnection();
            }
        };
        SchemaRefreshOrchestrator orchestrator = new SchemaRefreshOrchestrator(
                worker, refreshService, catalogRepository, opRepo, connectionFactory, Clock.systemUTC()
        );
        assertThatThrownBy(() -> orchestrator.refreshSchema(
                actor(),
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX")
        )).isInstanceOf(DiscoveryException.class);
        assertThat(opRepo.operations.get(0).state()).isEqualTo("FAILED");
    }

    private static class FakeOperationRepository implements SchemaRefreshOperationRepository {
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
                            op.updatedAt()
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
                if (op.operationId().equals(operationId)) {
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
        public int retry(String operationId, int newAttempt, int newRetryBackoffMs, long expectedFencingToken) {
            return 0;
        }

        @Override
        public List<SchemaRefreshOperation> findPending(String connectionId, Instant before) {
            return List.of();
        }

        @Override
        public int resetExpiredRunning(Instant now) {
            return 0;
        }
    }

    private static class FakeRefreshService extends SchemaRefreshService {
        private final boolean throwOnRefresh;

        public FakeRefreshService(CatalogRepository catalogRepository, SchemaDiscoveryPort discoveryPort, com.knowimran.rivoquery.connection.application.lifecycle.CanonicalIdGenerator idGenerator, boolean throwOnRefresh) {
            super(catalogRepository, discoveryPort, idGenerator);
            this.throwOnRefresh = throwOnRefresh;
        }

        @Override
        public SchemaSnapshot refresh(Connection connection, WorkspaceId workspaceId, ConnectionId connectionId, SchemaDiscoveryPort.DiscoveryPolicy policy) throws DiscoveryException {
            if (throwOnRefresh) {
                throw new DiscoveryException("simulated failure");
            }
            return null;
        }
    }

    private static class NoOpConnection implements Connection {
        @Override
        public Statement createStatement() throws java.sql.SQLException {
            return null;
        }

        @Override
        public PreparedStatement prepareStatement(String sql) throws java.sql.SQLException {
            return null;
        }

        @Override
        public CallableStatement prepareCall(String sql) throws java.sql.SQLException {
            return null;
        }

        @Override
        public String nativeSQL(String sql) throws java.sql.SQLException {
            return null;
        }

        @Override
        public void setAutoCommit(boolean autoCommit) throws java.sql.SQLException {
        }

        @Override
        public boolean getAutoCommit() throws java.sql.SQLException {
            return false;
        }

        @Override
        public void commit() throws java.sql.SQLException {
        }

        @Override
        public void rollback() throws java.sql.SQLException {
        }

        @Override
        public void close() throws java.sql.SQLException {
        }

        @Override
        public boolean isClosed() throws java.sql.SQLException {
            return false;
        }

        @Override
        public DatabaseMetaData getMetaData() throws java.sql.SQLException {
            return null;
        }

        @Override
        public void setReadOnly(boolean readOnly) throws java.sql.SQLException {
        }

        @Override
        public boolean isReadOnly() throws java.sql.SQLException {
            return false;
        }

        @Override
        public void setCatalog(String catalog) throws java.sql.SQLException {
        }

        @Override
        public String getCatalog() throws java.sql.SQLException {
            return null;
        }

        @Override
        public void setTransactionIsolation(int level) throws java.sql.SQLException {
        }

        @Override
        public int getTransactionIsolation() throws java.sql.SQLException {
            return 0;
        }

        @Override
        public SQLWarning getWarnings() throws java.sql.SQLException {
            return null;
        }

        @Override
        public void clearWarnings() throws java.sql.SQLException {
        }

        @Override
        public Statement createStatement(int resultSetType, int resultSetConcurrency) throws java.sql.SQLException {
            return null;
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws java.sql.SQLException {
            return null;
        }

        @Override
        public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws java.sql.SQLException {
            return null;
        }

        @Override
        public java.util.Map<String, Class<?>> getTypeMap() throws java.sql.SQLException {
            return null;
        }

        @Override
        public void setTypeMap(java.util.Map<String, Class<?>> map) throws java.sql.SQLException {
        }

        @Override
        public void setHoldability(int holdability) throws java.sql.SQLException {
        }

        @Override
        public int getHoldability() throws java.sql.SQLException {
            return 0;
        }

        @Override
        public Savepoint setSavepoint() throws java.sql.SQLException {
            return null;
        }

        @Override
        public Savepoint setSavepoint(String name) throws java.sql.SQLException {
            return null;
        }

        @Override
        public void rollback(Savepoint savepoint) throws java.sql.SQLException {
        }

        @Override
        public void releaseSavepoint(Savepoint savepoint) throws java.sql.SQLException {
        }

        @Override
        public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws java.sql.SQLException {
            return null;
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws java.sql.SQLException {
            return null;
        }

        @Override
        public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws java.sql.SQLException {
            return null;
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws java.sql.SQLException {
            return null;
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws java.sql.SQLException {
            return null;
        }

        @Override
        public PreparedStatement prepareStatement(String sql, String[] columnNames) throws java.sql.SQLException {
            return null;
        }

        @Override
        public Clob createClob() throws java.sql.SQLException {
            return null;
        }

        @Override
        public Blob createBlob() throws java.sql.SQLException {
            return null;
        }

        @Override
        public NClob createNClob() throws java.sql.SQLException {
            return null;
        }

        @Override
        public SQLXML createSQLXML() throws java.sql.SQLException {
            return null;
        }

        @Override
        public boolean isValid(int timeout) throws java.sql.SQLException {
            return false;
        }

        @Override
        public void setClientInfo(String name, String value) throws java.sql.SQLClientInfoException {
        }

        @Override
        public void setClientInfo(Properties properties) throws java.sql.SQLClientInfoException {
        }

        @Override
        public String getClientInfo(String name) throws java.sql.SQLException {
            return null;
        }

        @Override
        public Properties getClientInfo() throws java.sql.SQLException {
            return null;
        }

        @Override
        public Array createArrayOf(String typeName, Object[] elements) throws java.sql.SQLException {
            return null;
        }

        @Override
        public Struct createStruct(String typeName, Object[] attributes) throws java.sql.SQLException {
            return null;
        }

        @Override
        public void setSchema(String schema) throws java.sql.SQLException {
        }

        @Override
        public String getSchema() throws java.sql.SQLException {
            return null;
        }

        @Override
        public void abort(java.util.concurrent.Executor executor) throws java.sql.SQLException {
        }

        @Override
        public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) throws java.sql.SQLException {
        }

        @Override
        public int getNetworkTimeout() throws java.sql.SQLException {
            return 0;
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws java.sql.SQLException {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws java.sql.SQLException {
            return false;
        }
    }
}
