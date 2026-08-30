package com.knowimran.rivoquery.platform;

import com.knowimran.rivoquery.catalog.application.SchemaDiscoveryPort;
import com.knowimran.rivoquery.catalog.SchemaObjectPage;
import com.knowimran.rivoquery.catalog.SchemaSnapshot;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.platform.connector.mysql.MysqlSchemaDiscovery;
import com.knowimran.rivoquery.platform.connector.postgresql.PostgresqlSchemaDiscovery;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

class SchemaDiscoverySelector implements SchemaDiscoveryPort {
    private final PostgresqlSchemaDiscovery postgresql;
    private final MysqlSchemaDiscovery mysql;
    private final ThreadLocal<DatabaseKind> currentKind = new ThreadLocal<>();

    SchemaDiscoverySelector(PostgresqlSchemaDiscovery postgresql, MysqlSchemaDiscovery mysql) {
        this.postgresql = Objects.requireNonNull(postgresql, "postgresql");
        this.mysql = Objects.requireNonNull(mysql, "mysql");
    }

    @Override
    public SchemaSnapshot discover(Connection connection, WorkspaceId workspaceId, ConnectionId connectionId, DiscoveryPolicy policy) throws DiscoveryException {
        DatabaseKind kind = detectKind(connection);
        currentKind.set(kind);
        try {
            if (kind == DatabaseKind.POSTGRESQL) {
                return postgresql.discover(connection, workspaceId, connectionId, policy);
            }
            if (kind == DatabaseKind.MYSQL) {
                return mysql.discover(connection, workspaceId, connectionId, policy);
            }
            throw new DiscoveryException("unsupported database kind: " + kind);
        } catch (Exception e) {
            currentKind.remove();
            if (e instanceof DiscoveryException de) {
                throw de;
            }
            throw new DiscoveryException("discovery failed", e);
        }
    }

    @Override
    public DatabaseKind kind() {
        DatabaseKind kind = currentKind.get();
        if (kind == null) {
            throw new IllegalStateException("kind() called before discover()");
        }
        currentKind.remove();
        return kind;
    }

    private DatabaseKind detectKind(Connection connection) throws DiscoveryException {
        try {
            String productName = connection.getMetaData().getDatabaseProductName();
            if (productName == null) {
                throw new DiscoveryException("database product name is null");
            }
            String lower = productName.toLowerCase();
            if (lower.contains("postgresql") || lower.contains("postgres")) {
                return DatabaseKind.POSTGRESQL;
            }
            if (lower.contains("mysql")) {
                return DatabaseKind.MYSQL;
            }
            throw new DiscoveryException("unsupported database product: " + productName);
        } catch (SQLException e) {
            throw new DiscoveryException("failed to detect database kind", e);
        }
    }
}
