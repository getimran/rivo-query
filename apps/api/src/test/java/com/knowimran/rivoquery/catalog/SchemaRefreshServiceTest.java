package com.knowimran.rivoquery.catalog;

import com.knowimran.rivoquery.catalog.application.CatalogRepository;
import com.knowimran.rivoquery.catalog.application.SchemaDiscoveryPort;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshService;
import com.knowimran.rivoquery.catalog.application.SchemaSelectorPort;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.lifecycle.CanonicalIdGenerator;
import com.knowimran.rivoquery.connection.application.lifecycle.SecureRandomCanonicalIdGenerator;
import com.knowimran.rivoquery.identity.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaRefreshServiceTest {
    private static final CanonicalIdGenerator ID_GENERATOR = new SecureRandomCanonicalIdGenerator();

    @Test
    void normalizesAndDeduplicatesSystemObjects() throws SchemaDiscoveryPort.DiscoveryException {
        FakeRepository repo = new FakeRepository();
        SchemaRefreshService service = new SchemaRefreshService(repo, new FakeDiscoveryPort(DatabaseKind.POSTGRESQL), ID_GENERATOR);
        SchemaSnapshot snapshot = service.refresh(
                new NoOpConnection(),
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new SchemaDiscoveryPort.DiscoveryPolicy(List.of("public"), List.of(), false, true, 100)
        );
        assertThat(snapshot.objectCount()).isEqualTo(2);
        assertThat(snapshot.objects()).extracting(SchemaObject::canonicalName)
            .containsExactly(
                new CanonicalQualifiedName("public", "customers"),
                new CanonicalQualifiedName("public", "orders")
            );
        assertThat(repo.installed).isTrue();
    }

    @Test
    void canonicalizesNamesAndSorts() throws SchemaDiscoveryPort.DiscoveryException {
        FakeRepository repo = new FakeRepository();
        SchemaRefreshService service = new SchemaRefreshService(repo, new FakeDiscoveryPort(DatabaseKind.POSTGRESQL), ID_GENERATOR);
        SchemaSnapshot snapshot = service.refresh(
                new NoOpConnection(),
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new SchemaDiscoveryPort.DiscoveryPolicy(List.of("public"), List.of(), false, true, 100)
        );
        assertThat(snapshot.objects()).hasSize(2);
        assertThat(snapshot.objects().get(0).canonicalName().qualified()).isEqualTo("public.customers");
        assertThat(snapshot.objects().get(1).canonicalName().qualified()).isEqualTo("public.orders");
    }

    @Test
    void rejectsNullConnection() {
        SchemaRefreshService service = new SchemaRefreshService(new FakeRepository(), new FakeDiscoveryPort(DatabaseKind.POSTGRESQL), ID_GENERATOR);
        assertThatThrownBy(() -> service.refresh(null, new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"), new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"), policy()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullWorkspaceId() {
        SchemaRefreshService service = new SchemaRefreshService(new FakeRepository(), new FakeDiscoveryPort(DatabaseKind.POSTGRESQL), ID_GENERATOR);
        assertThatThrownBy(() -> service.refresh(new NoOpConnection(), null, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"), policy()))
            .isInstanceOf(NullPointerException.class);
    }

    private SchemaDiscoveryPort.DiscoveryPolicy policy() {
        return new SchemaDiscoveryPort.DiscoveryPolicy(List.of("public"), List.of(), false, true, 100);
    }

    private static class FakeRepository implements CatalogRepository {
        boolean installed;

        @Override
        public Optional<SchemaSnapshot> findCurrentSnapshot(WorkspaceId workspaceId, ConnectionId connectionId) {
            return Optional.empty();
        }

        @Override
        public Optional<SchemaSnapshot> findSnapshot(WorkspaceId workspaceId, ConnectionId connectionId, CatalogObjectId snapshotId) {
            return Optional.empty();
        }

        @Override
        public void insert(SchemaSnapshot snapshot) {
        }

        @Override
        public void installCurrentSnapshot(WorkspaceId workspaceId, ConnectionId connectionId, SchemaSnapshot snapshot) {
            installed = true;
        }

        @Override
        public SchemaStatus findStatus(WorkspaceId workspaceId, ConnectionId connectionId) {
            return null;
        }

        @Override
        public SchemaObjectPage findObjects(WorkspaceId workspaceId, ConnectionId connectionId, String cursor, int limit) {
            return null;
        }
    }

    private static class FakeDiscoveryPort implements SchemaDiscoveryPort {
        private final DatabaseKind kind;

        FakeDiscoveryPort(DatabaseKind kind) {
            this.kind = kind;
        }

        @Override
        public DatabaseKind kind() {
            return kind;
        }

        @Override
        public SchemaSnapshot discover(Connection connection, WorkspaceId workspaceId, ConnectionId connectionId, DiscoveryPolicy policy) throws DiscoveryException {
            return new SchemaSnapshot(
                "raw_snap",
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                connectionId,
                new SchemaFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                Instant.parse("2026-01-01T00:00:00Z"),
                3,
                List.of(
                    object("pg_catalog", "pg_class"),
                    object("public", "orders"),
                    object("public", "Customers")
                )
            );
        }

        private SchemaObject object(String schema, String object) {
            return new SchemaObject(
                new RawQualifiedName(schema, object),
                new CanonicalQualifiedName(schema, object),
                SchemaObjectType.TABLE,
                null,
                List.of(new SchemaColumn("id", ColumnTypeFamily.NUMERIC, false, null, ColumnClassification.NONE)),
                SystemObjectDenylist.isSystemSchema(DatabaseKind.POSTGRESQL, schema),
                false
            );
        }
    }

    private static class NoOpConnection implements Connection {
        @Override
        public Statement createStatement() throws SQLException {
            return null;
        }

        @Override
        public PreparedStatement prepareStatement(String sql) throws SQLException {
            return null;
        }

        @Override
        public CallableStatement prepareCall(String sql) throws SQLException {
            return null;
        }

        @Override
        public String nativeSQL(String sql) throws SQLException {
            return null;
        }

        @Override
        public void setAutoCommit(boolean autoCommit) throws SQLException {
        }

        @Override
        public boolean getAutoCommit() throws SQLException {
            return false;
        }

        @Override
        public void commit() throws SQLException {
        }

        @Override
        public void rollback() throws SQLException {
        }

        @Override
        public void close() throws SQLException {
        }

        @Override
        public boolean isClosed() throws SQLException {
            return false;
        }

        @Override
        public DatabaseMetaData getMetaData() throws SQLException {
            return null;
        }

        @Override
        public void setReadOnly(boolean readOnly) throws SQLException {
        }

        @Override
        public boolean isReadOnly() throws SQLException {
            return false;
        }

        @Override
        public void setCatalog(String catalog) throws SQLException {
        }

        @Override
        public String getCatalog() throws SQLException {
            return null;
        }

        @Override
        public void setTransactionIsolation(int level) throws SQLException {
        }

        @Override
        public int getTransactionIsolation() throws SQLException {
            return 0;
        }

        @Override
        public SQLWarning getWarnings() throws SQLException {
            return null;
        }

        @Override
        public void clearWarnings() throws SQLException {
        }

        @Override
        public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
            return null;
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return null;
        }

        @Override
        public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return null;
        }

        @Override
        public java.util.Map<String, Class<?>> getTypeMap() throws SQLException {
            return null;
        }

        @Override
        public void setTypeMap(java.util.Map<String, Class<?>> map) throws SQLException {
        }

        @Override
        public void setHoldability(int holdability) throws SQLException {
        }

        @Override
        public int getHoldability() throws SQLException {
            return 0;
        }

        @Override
        public Savepoint setSavepoint() throws SQLException {
            return null;
        }

        @Override
        public Savepoint setSavepoint(String name) throws SQLException {
            return null;
        }

        @Override
        public void rollback(Savepoint savepoint) throws SQLException {
        }

        @Override
        public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        }

        @Override
        public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return null;
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return null;
        }

        @Override
        public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return null;
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
            return null;
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
            return null;
        }

        @Override
        public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
            return null;
        }

        @Override
        public Clob createClob() throws SQLException {
            return null;
        }

        @Override
        public Blob createBlob() throws SQLException {
            return null;
        }

        @Override
        public NClob createNClob() throws SQLException {
            return null;
        }

        @Override
        public SQLXML createSQLXML() throws SQLException {
            return null;
        }

        @Override
        public boolean isValid(int timeout) throws SQLException {
            return false;
        }

        @Override
        public void setClientInfo(String name, String value) throws SQLClientInfoException {
        }

        @Override
        public void setClientInfo(Properties properties) throws SQLClientInfoException {
        }

        @Override
        public String getClientInfo(String name) throws SQLException {
            return null;
        }

        @Override
        public Properties getClientInfo() throws SQLException {
            return null;
        }

        @Override
        public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
            return null;
        }

        @Override
        public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
            return null;
        }

        @Override
        public void setSchema(String schema) throws SQLException {
        }

        @Override
        public String getSchema() throws SQLException {
            return null;
        }

        @Override
        public void abort(Executor executor) throws SQLException {
        }

        @Override
        public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        }

        @Override
        public int getNetworkTimeout() throws SQLException {
            return 0;
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return false;
        }
    }
}
