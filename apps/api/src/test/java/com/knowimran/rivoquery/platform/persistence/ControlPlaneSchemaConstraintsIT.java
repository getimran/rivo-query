package com.knowimran.rivoquery.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.RivoQueryApplication;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the P2-060 control-plane schema migrates cleanly from an empty
 * database, that composite tenant/resource foreign keys reject cross-tenant
 * references at the database level, and that the connection lifecycle audit
 * table has no update/delete/truncate path - including for the runtime
 * application role, which is deliberately distinct from (and never owned by)
 * the migration-owner role Flyway itself connects as.
 */
@Testcontainers
class ControlPlaneSchemaConstraintsIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:17.6-alpine3.22@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    private static final String RUNTIME_APP_USERNAME = "rivoquery_app_it";
    private static final String RUNTIME_APP_PASSWORD = "rivoquery-app-it-synthetic-pw";

    private static ConfigurableApplicationContext CONTEXT;
    private static JdbcTemplate JDBC;

    /**
     * A separate, unpooled connection authenticated as the migration-owner
     * (table-owning) role, used only to prove that the append-only triggers
     * themselves reject UPDATE/DELETE/TRUNCATE even for a role that is not
     * subject to the runtime role's revoked grants - i.e. that the trigger
     * is real defense-in-depth, not merely redundant with the grants.
     */
    private static JdbcTemplate MIGRATOR_JDBC;

    @BeforeAll
    static void migrateFromEmpty() {
        // Flyway connects as the container's own bootstrap superuser (the
        // migration owner). V2 no longer creates RUNTIME_APP_USERNAME itself
        // (see the migration's own comments on why); it must already exist,
        // exactly like production/local provisioning, so it is created here
        // out-of-band via ControlDatabaseRoleFixture before the application
        // starts. The application's own JdbcTemplate bean (used by JDBC
        // below, and by every other test in this class) connects as that
        // restricted role, not as the migration owner. This is what makes
        // the append-only assertions below a real guarantee rather than one
        // that only holds by coincidence of a shared role.
        ControlDatabaseRoleFixture.createRuntimeRole(POSTGRES, RUNTIME_APP_USERNAME, RUNTIME_APP_PASSWORD);
        Map<String, Object> properties = Map.of(
                "spring.datasource.url", POSTGRES.getJdbcUrl(),
                "spring.datasource.username", RUNTIME_APP_USERNAME,
                "spring.datasource.password", RUNTIME_APP_PASSWORD,
                "spring.flyway.user", POSTGRES.getUsername(),
                "spring.flyway.password", POSTGRES.getPassword());
        CONTEXT = new SpringApplicationBuilder(RivoQueryApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run("--debug=false");
        JDBC = CONTEXT.getBean(JdbcTemplate.class);

        var migratorDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        MIGRATOR_JDBC = new JdbcTemplate(migratorDataSource);
    }

    @AfterAll
    static void shutdown() {
        if (CONTEXT != null) {
            CONTEXT.close();
        }
    }

    @Test
    void everyControlPlaneTableExistsAfterMigratingFromEmpty() {
        var expectedTables = java.util.List.of(
                "workspaces", "actors", "memberships", "connections",
                "connection_secret_references", "connection_permission_collections",
                "connection_permission_grants", "durable_operations", "idempotency_keys",
                "connection_lifecycle_audit_events", "connection_profiles");

        for (String table : expectedTables) {
            Integer count = JDBC.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            assertThat(count).as("table %s exists", table).isEqualTo(1);
        }

        Integer appliedMigrations = JDBC.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class);
        // 3 migrations as of V3 (connection_profiles + idempotency_keys wiring, P2-130).
        assertThat(appliedMigrations).isEqualTo(3);
    }

    @Test
    void connectionRequiresAnExistingWorkspace() {
        assertThatThrownBy(() -> JDBC.update(
                        "INSERT INTO connections "
                                + "(workspace_id, connection_id, kind, state, version, latest_secret_version) "
                                + "VALUES (?, ?, 'POSTGRESQL', 'PENDING_SECRET', 1, 0)",
                        "wsp_00000000000000000000000000", "con_00000000000000000000000001"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void connectionPermissionGrantRejectsAMembershipFromAForeignTenant() {
        String workspaceA = insertWorkspace("A1");
        String workspaceB = insertWorkspace("B1");
        String connectionInA = insertPendingSecretConnection(workspaceA, "C1");
        String membershipInB = insertMembership(workspaceB, insertActor("iss-a", "sub-a"), "M1");
        insertPermissionCollection(workspaceA, connectionInA, 1L);

        assertThatThrownBy(() -> JDBC.update(
                        "INSERT INTO connection_permission_grants "
                                + "(workspace_id, connection_id, membership_id, permissions) "
                                + "VALUES (?, ?, ?, ARRAY['VIEW_SCHEMA'])",
                        workspaceA, connectionInA, membershipInB))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void durableOperationRejectsAConnectionFromAForeignTenant() {
        String workspaceA = insertWorkspace("A2");
        String workspaceB = insertWorkspace("B2");
        String connectionInB = insertPendingSecretConnection(workspaceB, "C2");
        String actor = insertActor("iss-b", "sub-b");

        assertThatThrownBy(() -> JDBC.update(
                        "INSERT INTO durable_operations "
                                + "(workspace_id, operation_id, actor_id, connection_id, action, state) "
                                + "VALUES (?, ?, ?, ?, 'CONNECTION_CREATE', 'PENDING')",
                        workspaceA, "op_00000000000000000000000002", actor, connectionInB))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void connectionPermissionGrantRejectsAnEmptyOrUnknownPermissionSet() {
        String workspace = insertWorkspace("E1");
        String connection = insertPendingSecretConnection(workspace, "E1");
        String membership = insertMembership(workspace, insertActor("iss-e", "sub-e"), "E1");
        insertPermissionCollection(workspace, connection, 1L);

        assertThatThrownBy(() -> JDBC.update(
                        "INSERT INTO connection_permission_grants "
                                + "(workspace_id, connection_id, membership_id, permissions) "
                                + "VALUES (?, ?, ?, ARRAY[]::text[])",
                        workspace, connection, membership))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> JDBC.update(
                        "INSERT INTO connection_permission_grants "
                                + "(workspace_id, connection_id, membership_id, permissions) "
                                + "VALUES (?, ?, ?, ARRAY['MANAGE'])",
                        workspace, connection, membership))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void connectionLifecycleAuditEventsCannotBeUpdatedOrDeletedEvenByTheTableOwner() {
        String workspace = insertWorkspace("AUD");
        String connection = insertPendingSecretConnection(workspace, "AUD");
        String actor = insertActor("iss-audit", "sub-audit");
        JDBC.update(
                "INSERT INTO connection_lifecycle_audit_events "
                        + "(workspace_id, actor_id, connection_id, request_id, action, outcome, category, occurred_at) "
                        + "VALUES (?, ?, ?, 'req-audit-1', 'CREATE', 'SUCCEEDED', 'AUTHORIZATION', ?)",
                workspace, actor, connection, Timestamp.from(Instant.parse("2026-08-23T00:00:00Z")));

        // Run as the migration-owner role (not the restricted runtime role
        // JDBC otherwise uses) so this proves the trigger itself rejects the
        // mutation, not merely that the runtime role lacks a grant.
        assertThatThrownBy(() -> MIGRATOR_JDBC.update(
                        "UPDATE connection_lifecycle_audit_events SET outcome = 'DENIED' WHERE workspace_id = ?",
                        workspace))
                .isInstanceOfSatisfying(
                        DataAccessException.class,
                        exception -> assertThat(exception.getMostSpecificCause().getMessage())
                                .contains("append-only"));

        assertThatThrownBy(() -> MIGRATOR_JDBC.update(
                        "DELETE FROM connection_lifecycle_audit_events WHERE workspace_id = ?", workspace))
                .isInstanceOfSatisfying(
                        DataAccessException.class,
                        exception -> assertThat(exception.getMostSpecificCause().getMessage())
                                .contains("append-only"));

        Integer remaining = JDBC.queryForObject(
                "SELECT COUNT(*) FROM connection_lifecycle_audit_events WHERE workspace_id = ?",
                Integer.class, workspace);
        assertThat(remaining).isEqualTo(1);
    }

    @Test
    void connectionLifecycleAuditEventsCannotBeTruncatedEvenByTheTableOwner() {
        String workspace = insertWorkspace("TRN");
        String connection = insertPendingSecretConnection(workspace, "TRN");
        String actor = insertActor("iss-trunc", "sub-trunc");
        JDBC.update(
                "INSERT INTO connection_lifecycle_audit_events "
                        + "(workspace_id, actor_id, connection_id, request_id, action, outcome, category, occurred_at) "
                        + "VALUES (?, ?, ?, 'req-trunc-1', 'CREATE', 'SUCCEEDED', 'AUTHORIZATION', ?)",
                workspace, actor, connection, Timestamp.from(Instant.parse("2026-08-23T00:00:00Z")));

        // BEFORE UPDATE/DELETE row-level triggers never fire for TRUNCATE;
        // only the dedicated BEFORE TRUNCATE statement-level trigger does.
        // The migration owner role has TRUNCATE privilege (it is the table
        // owner), so this specifically proves the trigger - not a missing
        // grant - is what stops it.
        assertThatThrownBy(() -> MIGRATOR_JDBC.execute("TRUNCATE connection_lifecycle_audit_events"))
                .isInstanceOfSatisfying(
                        DataAccessException.class,
                        exception -> assertThat(exception.getMostSpecificCause().getMessage())
                                .contains("append-only"));

        Integer remaining = JDBC.queryForObject(
                "SELECT COUNT(*) FROM connection_lifecycle_audit_events WHERE workspace_id = ?",
                Integer.class, workspace);
        assertThat(remaining).isEqualTo(1);
    }

    @Test
    void theRuntimeApplicationRoleCannotDisableTheAuditTriggersToDeleteAroundThem() {
        String workspace = insertWorkspace("ESC");
        String connection = insertPendingSecretConnection(workspace, "ESC");
        String actor = insertActor("iss-esc", "sub-esc");
        JDBC.update(
                "INSERT INTO connection_lifecycle_audit_events "
                        + "(workspace_id, actor_id, connection_id, request_id, action, outcome, category, occurred_at) "
                        + "VALUES (?, ?, ?, 'req-esc-1', 'CREATE', 'SUCCEEDED', 'AUTHORIZATION', ?)",
                workspace, actor, connection, Timestamp.from(Instant.parse("2026-08-23T00:00:00Z")));

        // JDBC connects as the restricted runtime role, which is never the
        // table's owner, so ALTER TABLE ... DISABLE TRIGGER is rejected by a
        // privilege check before it ever reaches the trigger machinery - the
        // opposite failure mode from the two tests above.
        assertThatThrownBy(() -> JDBC.execute(
                        "ALTER TABLE connection_lifecycle_audit_events DISABLE TRIGGER USER"))
                .isInstanceOfSatisfying(
                        DataAccessException.class,
                        exception -> assertThat(exception.getMostSpecificCause().getMessage())
                                .doesNotContain("append-only"));

        assertThatThrownBy(() -> JDBC.update(
                        "DELETE FROM connection_lifecycle_audit_events WHERE workspace_id = ?", workspace))
                .isInstanceOfSatisfying(
                        DataAccessException.class,
                        exception -> assertThat(exception.getMostSpecificCause().getMessage())
                                .doesNotContain("append-only"));

        Integer remaining = JDBC.queryForObject(
                "SELECT COUNT(*) FROM connection_lifecycle_audit_events WHERE workspace_id = ?",
                Integer.class, workspace);
        assertThat(remaining).isEqualTo(1);
    }

    @Test
    void aDeniedAuditEventCanBeRecordedForAConnectionThatWasNeverCreated() {
        String workspace = insertWorkspace("PRB");
        String actor = insertActor("iss-probe", "sub-probe");
        // A well-formed connection id that was never inserted into
        // `connections` - e.g. an authorization-denial/probing attempt
        // against a connection the actor does not own or that does not
        // exist. ADR-0011 requires every execution attempt, including
        // denials, to be auditable; connection_id is format-validated but
        // deliberately not foreign-keyed to `connections` so this insert
        // must succeed.
        String neverCreatedConnection = "con_" + ulid("never-created");

        JDBC.update(
                "INSERT INTO connection_lifecycle_audit_events "
                        + "(workspace_id, actor_id, connection_id, request_id, action, outcome, category, occurred_at) "
                        + "VALUES (?, ?, ?, 'req-probe-1', 'CREATE', 'DENIED', 'AUTHORIZATION', ?)",
                workspace, actor, neverCreatedConnection, Timestamp.from(Instant.parse("2026-08-23T00:00:00Z")));

        Integer count = JDBC.queryForObject(
                "SELECT COUNT(*) FROM connection_lifecycle_audit_events "
                        + "WHERE workspace_id = ? AND connection_id = ? AND outcome = 'DENIED'",
                Integer.class, workspace, neverCreatedConnection);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void auditEventConnectionIdIsStillFormatValidated() {
        String workspace = insertWorkspace("FMT");
        String actor = insertActor("iss-fmt", "sub-fmt");

        assertThatThrownBy(() -> JDBC.update(
                        "INSERT INTO connection_lifecycle_audit_events "
                                + "(workspace_id, actor_id, connection_id, request_id, action, outcome, category, occurred_at) "
                                + "VALUES (?, ?, 'not-a-connection-id', 'req-fmt-1', 'CREATE', 'DENIED', 'AUTHORIZATION', ?)",
                        workspace, actor, Timestamp.from(Instant.parse("2026-08-23T00:00:00Z"))))
                .isInstanceOf(DataAccessException.class);
    }

    private static String insertWorkspace(String suffix) {
        String id = "wsp_" + ulid(suffix);
        JDBC.update("INSERT INTO workspaces (workspace_id, display_name) VALUES (?, ?)", id, "workspace-" + suffix);
        return id;
    }

    private static String insertActor(String issuer, String subject) {
        String id = "act_" + ulid(issuer + subject);
        JDBC.update("INSERT INTO actors (actor_id, issuer, subject) VALUES (?, ?, ?)", id, issuer, subject);
        return id;
    }

    private static String insertMembership(String workspaceId, String actorId, String suffix) {
        String id = "mem_" + ulid(suffix);
        JDBC.update(
                "INSERT INTO memberships (workspace_id, membership_id, actor_id, role, status) "
                        + "VALUES (?, ?, ?, 'ADMINISTRATOR', 'ACTIVE')",
                workspaceId, id, actorId);
        return id;
    }

    private static String insertPendingSecretConnection(String workspaceId, String suffix) {
        String id = "con_" + ulid(suffix);
        JDBC.update(
                "INSERT INTO connections "
                        + "(workspace_id, connection_id, kind, state, version, latest_secret_version) "
                        + "VALUES (?, ?, 'POSTGRESQL', 'PENDING_SECRET', 1, 0)",
                workspaceId, id);
        return id;
    }

    private static void insertPermissionCollection(String workspaceId, String connectionId, long version) {
        JDBC.update(
                "INSERT INTO connection_permission_collections (workspace_id, connection_id, collection_version) "
                        + "VALUES (?, ?, ?)",
                workspaceId, connectionId, version);
    }

    private static final java.util.concurrent.atomic.AtomicLong ID_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Produces a canonical-length, collision-free suffix for each call within
     * this test run. {@code seed} is accepted only for call-site readability;
     * uniqueness comes from a monotonic counter, not the seed's hash, so two
     * fixtures can never collide onto the same opaque id.
     */
    private static String ulid(String seed) {
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        long value = ID_SEQUENCE.incrementAndGet();
        char[] chars = new char[26];
        for (int i = 25; i >= 0; i--) {
            chars[i] = alphabet.charAt((int) (value % alphabet.length()));
            value /= alphabet.length();
        }
        return new String(chars);
    }
}
