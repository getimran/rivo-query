package com.knowimran.rivoquery.platform.operation;

import com.knowimran.rivoquery.catalog.application.SchemaRefreshConnectionFactory;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.application.ConnectionProfileRepository;
import com.knowimran.rivoquery.connection.application.ConnectionRepository;
import com.knowimran.rivoquery.connection.application.secret.SecretMaterialConsumer;
import com.knowimran.rivoquery.connection.application.secret.SecretOperations;
import com.knowimran.rivoquery.connection.application.secret.SecretUsePurpose;
import com.knowimran.rivoquery.platform.connector.ConnectorDialect;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;

public final class SchemaRefreshJdbcConnectionFactory implements SchemaRefreshConnectionFactory {
    private final ConnectionRepository connections;
    private final ConnectionProfileRepository profiles;
    private final SecretOperations secretOperations;
    private final ConnectorDialect postgresqlDialect;
    private final ConnectorDialect mysqlDialect;

    public SchemaRefreshJdbcConnectionFactory(
            ConnectionRepository connections,
            ConnectionProfileRepository profiles,
            SecretOperations secretOperations,
            ConnectorDialect postgresqlDialect,
            ConnectorDialect mysqlDialect) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.secretOperations = Objects.requireNonNull(secretOperations, "secretOperations");
        this.postgresqlDialect = Objects.requireNonNull(postgresqlDialect, "postgresqlDialect");
        this.mysqlDialect = Objects.requireNonNull(mysqlDialect, "mysqlDialect");
    }

    @Override
    public Connection open(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId) throws SQLException {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        DatabaseConnection connection = connections.findByWorkspaceAndId(workspaceId, connectionId)
                .orElseThrow(() -> new SQLException("connection not found: " + connectionId));
        connection.authorize(actor);
        var profile = profiles.find(workspaceId, connectionId)
                .or(() -> java.util.Optional.of(com.knowimran.rivoquery.connection.application.ConnectionProfile.placeholder(workspaceId, connectionId)))
                .orElseThrow(() -> new SQLException("connection profile not found: " + connectionId));
        ConnectorDialect dialect = dialectFor(connection.kind());
        var target = dialect.buildConnectionTarget(profile.endpoint());
        Map<String, String> properties = Map.copyOf(target.properties());
        Connection[] result = new Connection[1];
        java.sql.SQLException[] error = new java.sql.SQLException[1];
        secretOperations.consume(actor, connectionId, SecretUsePurpose.CONNECTION_TEST, new com.knowimran.rivoquery.connection.application.Deadline(java.time.Instant.now().plusSeconds(30)), material -> {
            String credential = new String(material, java.nio.charset.StandardCharsets.UTF_8);
            int sep = credential.indexOf('\0');
            String username = sep >= 0 ? credential.substring(0, sep) : credential;
            String password = sep >= 0 ? credential.substring(sep + 1) : "";
            java.util.Properties driverProperties = new java.util.Properties();
            driverProperties.putAll(properties);
            driverProperties.setProperty("user", username);
            driverProperties.setProperty("password", password);
            try {
                result[0] = DriverManager.getConnection(target.jdbcUrl(), driverProperties);
            } catch (java.sql.SQLException e) {
                error[0] = e;
            }
        });
        if (error[0] != null) {
            throw new java.sql.SQLException("failed to open connection for " + connectionId, error[0]);
        }
        if (result[0] == null) {
            throw new SQLException("failed to open connection for " + connectionId);
        }
        return result[0];
    }

    private ConnectorDialect dialectFor(DatabaseKind kind) {
        if (kind == DatabaseKind.POSTGRESQL) {
            return postgresqlDialect;
        }
        if (kind == DatabaseKind.MYSQL) {
            return mysqlDialect;
        }
        throw new IllegalArgumentException("unsupported database kind: " + kind);
    }
}
