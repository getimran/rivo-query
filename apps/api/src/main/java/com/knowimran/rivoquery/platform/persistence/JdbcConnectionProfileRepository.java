package com.knowimran.rivoquery.platform.persistence;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.application.ConnectionLimits;
import com.knowimran.rivoquery.connection.application.ConnectionObjectPolicy;
import com.knowimran.rivoquery.connection.application.ConnectionProfile;
import com.knowimran.rivoquery.connection.application.ConnectionProfileRepository;
import com.knowimran.rivoquery.connection.application.ConnectionTlsMode;
import com.knowimran.rivoquery.connection.application.destination.StructuredEndpoint;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.sql.Array;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** See {@link ConnectionProfile} for why this adapter exists. */
// Not final: Spring's default proxy-target-class=true AOP configuration
// requires a CGLIB subclass for every @Repository bean (persistence
// exception translation applies regardless of @Transactional use).
@Repository
public class JdbcConnectionProfileRepository implements ConnectionProfileRepository {

    private static final String FIND_QUERY = """
            SELECT name, endpoint_host, endpoint_port, endpoint_database, tls_mode,
                   custom_ca_configured, client_certificate_configured, tcp_keep_alive,
                   allowed_schemas, allowed_tables, statement_timeout_ms, max_rows,
                   created_at, updated_at
            FROM connection_profiles
            WHERE workspace_id = ? AND connection_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcConnectionProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public Optional<ConnectionProfile> find(WorkspaceId workspaceId, ConnectionId connectionId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        List<ConnectionProfile> rows = jdbcTemplate.query(
                FIND_QUERY,
                (resultSet, rowNumber) -> new ConnectionProfile(
                        workspaceId,
                        connectionId,
                        resultSet.getString("name"),
                        new StructuredEndpoint(
                                resultSet.getString("endpoint_host"),
                                resultSet.getInt("endpoint_port"),
                                resultSet.getString("endpoint_database")),
                        ConnectionTlsMode.valueOf(resultSet.getString("tls_mode")),
                        resultSet.getBoolean("custom_ca_configured"),
                        resultSet.getBoolean("client_certificate_configured"),
                        resultSet.getBoolean("tcp_keep_alive"),
                        new ConnectionObjectPolicy(
                                toSet(resultSet.getArray("allowed_schemas")),
                                toSet(resultSet.getArray("allowed_tables"))),
                        new ConnectionLimits(
                                resultSet.getInt("statement_timeout_ms"), resultSet.getInt("max_rows")),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()),
                workspaceId.value(), connectionId.value());
        return rows.stream().findFirst();
    }

    @Override
    public void insert(ConnectionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        write("""
                INSERT INTO connection_profiles
                    (workspace_id, connection_id, name, endpoint_host, endpoint_port, endpoint_database,
                     tls_mode, custom_ca_configured, client_certificate_configured, tcp_keep_alive,
                     allowed_schemas, allowed_tables, statement_timeout_ms, max_rows, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, profile);
    }

    /**
     * {@code updated_at} is bound explicitly from {@code profile.updatedAt()}
     * (S3 fix) rather than the SQL {@code now()} it previously used, so the
     * caller's own {@link ConnectionProfile} - which is what the same
     * request's response is built from - always reflects exactly what this
     * write persisted; {@code created_at} is deliberately absent from the
     * {@code DO UPDATE SET} list so an existing row's true creation time
     * survives every subsequent PATCH untouched.
     */
    @Override
    public void save(ConnectionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        write("""
                INSERT INTO connection_profiles
                    (workspace_id, connection_id, name, endpoint_host, endpoint_port, endpoint_database,
                     tls_mode, custom_ca_configured, client_certificate_configured, tcp_keep_alive,
                     allowed_schemas, allowed_tables, statement_timeout_ms, max_rows, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (workspace_id, connection_id) DO UPDATE SET
                    name = EXCLUDED.name, endpoint_host = EXCLUDED.endpoint_host,
                    endpoint_port = EXCLUDED.endpoint_port, endpoint_database = EXCLUDED.endpoint_database,
                    tls_mode = EXCLUDED.tls_mode, custom_ca_configured = EXCLUDED.custom_ca_configured,
                    client_certificate_configured = EXCLUDED.client_certificate_configured,
                    tcp_keep_alive = EXCLUDED.tcp_keep_alive, allowed_schemas = EXCLUDED.allowed_schemas,
                    allowed_tables = EXCLUDED.allowed_tables, statement_timeout_ms = EXCLUDED.statement_timeout_ms,
                    max_rows = EXCLUDED.max_rows, updated_at = EXCLUDED.updated_at
                """, profile);
    }

    private void write(String sql, ConnectionProfile profile) {
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setString(1, profile.workspaceId().value());
            statement.setString(2, profile.connectionId().value());
            statement.setString(3, profile.name());
            statement.setString(4, profile.endpoint().host());
            statement.setInt(5, profile.endpoint().port());
            statement.setString(6, profile.endpoint().databaseName());
            statement.setString(7, profile.tlsMode().name());
            statement.setBoolean(8, profile.customCaConfigured());
            statement.setBoolean(9, profile.clientCertificateConfigured());
            statement.setBoolean(10, profile.tcpKeepAlive());
            statement.setArray(11, connection.createArrayOf("text", profile.objectPolicy().allowedSchemas().toArray()));
            statement.setArray(12, connection.createArrayOf("text", profile.objectPolicy().allowedTables().toArray()));
            statement.setInt(13, profile.limits().statementTimeoutMs());
            statement.setInt(14, profile.limits().maxRows());
            statement.setTimestamp(15, java.sql.Timestamp.from(profile.createdAt()));
            statement.setTimestamp(16, java.sql.Timestamp.from(profile.updatedAt()));
            return statement;
        });
    }

    @Override
    public void delete(WorkspaceId workspaceId, ConnectionId connectionId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        jdbcTemplate.update(
                "DELETE FROM connection_profiles WHERE workspace_id = ? AND connection_id = ?",
                workspaceId.value(), connectionId.value());
    }

    private static Set<String> toSet(Array sqlArray) throws java.sql.SQLException {
        String[] values = (String[]) sqlArray.getArray();
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
