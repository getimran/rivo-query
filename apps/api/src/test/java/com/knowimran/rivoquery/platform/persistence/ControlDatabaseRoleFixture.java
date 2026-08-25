package com.knowimran.rivoquery.platform.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Test-only equivalent of the out-of-band role provisioning that
 * {@code deploy/local/postgres-initdb/} performs for local development and
 * {@code docs/OPERATIONS.md} requires for staging/production.
 *
 * <p>{@code V2__create_identity_and_connection_control_plane.sql} no longer
 * creates the runtime application role itself (see the migration's own
 * comments): it only grants privileges to a role that must already exist,
 * and fails the migration loudly otherwise. Every integration test that
 * boots the full Spring context against a fresh Testcontainers PostgreSQL
 * instance must therefore create that role first, using a direct JDBC
 * connection authenticated as the container's own bootstrap superuser -
 * never through Flyway's or the application's own (logged) connection -
 * exactly like the production/local provisioning path this mirrors.
 */
public final class ControlDatabaseRoleFixture {

    private static final java.util.regex.Pattern SAFE_ROLE_NAME = java.util.regex.Pattern.compile("[a-z_][a-z0-9_]*");

    private ControlDatabaseRoleFixture() {}

    public static void createRuntimeRole(PostgreSQLContainer<?> postgres, String username, String password) {
        if (!SAFE_ROLE_NAME.matcher(username).matches()) {
            throw new IllegalArgumentException("Unsafe test role name: " + username);
        }
        String escapedPassword = password.replace("'", "''");
        String sql = "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = '"
                + username + "') THEN CREATE ROLE " + username + " LOGIN PASSWORD '" + escapedPassword
                + "'; END IF; END $$;";

        try (Connection connection =
                        DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to provision test runtime role " + username, exception);
        }
    }
}
