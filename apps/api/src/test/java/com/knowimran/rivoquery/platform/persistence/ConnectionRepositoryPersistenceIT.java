package com.knowimran.rivoquery.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.RivoQueryApplication;
import com.knowimran.rivoquery.connection.ConflictingSecretReferenceException;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionState;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.StaleConnectionVersionException;
import com.knowimran.rivoquery.connection.application.secret.BoundSecretReference;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretPurpose;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-PostgreSQL coverage for {@link JdbcConnectionRepository}: tenant
 * scoping (a foreign workspace never sees another tenant's connection) and
 * optimistic-version conflict (a stale {@code compareAndSet} throws the
 * domain's {@link StaleConnectionVersionException} rather than silently
 * overwriting a concurrent writer).
 */
@Testcontainers
class ConnectionRepositoryPersistenceIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:17.6-alpine3.22@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    // A restricted role distinct from the container's bootstrap superuser
    // (which Flyway migrates as); see ControlDatabaseRoleFixture and V2's
    // own comments on why this split must hold and why V2 no longer creates
    // this role itself.
    private static final String RUNTIME_APP_USERNAME = "rivoquery_app_it";
    private static final String RUNTIME_APP_PASSWORD = "rivoquery-app-it-synthetic-pw";

    private static ConfigurableApplicationContext CONTEXT;
    private static JdbcTemplate JDBC;
    private static JdbcConnectionRepository REPOSITORY;

    private static final WorkspaceId WORKSPACE_ONE = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final WorkspaceId WORKSPACE_TWO = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY");
    private static final ConnectionId CONNECTION = new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX");

    @BeforeAll
    static void migrateFromEmpty() {
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
        // Fetched from the Spring context (not `new`) so that @Transactional
        // on compareAndSet is backed by a real AOP proxy; see
        // compareAndSetRollsBackTheSecretReferenceInsertWhenTheExpectedVersionIsStale
        // below, which fails without that proxy.
        REPOSITORY = CONTEXT.getBean(JdbcConnectionRepository.class);
    }

    @AfterAll
    static void shutdown() {
        if (CONTEXT != null) {
            CONTEXT.close();
        }
    }

    @BeforeEach
    void resetData() {
        // connection_lifecycle_audit_events is deliberately never reset here:
        // none of this class's tests write to it, and (per
        // ControlPlaneSchemaConstraintsIT) it has no delete path even for
        // the migration-owner role, let alone the restricted runtime role
        // this JdbcTemplate now genuinely connects as.
        JDBC.update("DELETE FROM idempotency_keys");
        JDBC.update("DELETE FROM durable_operations");
        JDBC.update("DELETE FROM connection_permission_grants");
        JDBC.update("DELETE FROM connection_permission_collections");
        // connections.current_secret_version -> connection_secret_references is a
        // composite FK, and connection_secret_references -> connections is the
        // reverse FK; break the cycle by clearing the pointer column first. Reset
        // state to PENDING_SECRET in the same statement so every row still
        // satisfies the state/current-secret CHECK constraint after clearing it.
        JDBC.update(
                "UPDATE connections SET state = 'PENDING_SECRET', current_secret_version = NULL, "
                        + "secret_retire_at = NULL");
        JDBC.update("DELETE FROM connection_secret_references");
        JDBC.update("DELETE FROM connections");
        JDBC.update("DELETE FROM memberships");
        JDBC.update("DELETE FROM actors");
        JDBC.update("DELETE FROM workspaces");
        JDBC.update("INSERT INTO workspaces (workspace_id, display_name) VALUES (?, 'One')", WORKSPACE_ONE.value());
        JDBC.update("INSERT INTO workspaces (workspace_id, display_name) VALUES (?, 'Two')", WORKSPACE_TWO.value());
    }

    @Test
    void insertThenFindRoundTripsAConnectionWithNoSecretYet() {
        var reserved = DatabaseConnection.reserve(WORKSPACE_ONE, CONNECTION, DatabaseKind.POSTGRESQL);
        REPOSITORY.insert(reserved);

        var found = REPOSITORY.findByWorkspaceAndId(WORKSPACE_ONE, CONNECTION);

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().state()).isEqualTo(ConnectionState.PENDING_SECRET);
        assertThat(found.orElseThrow().version()).isEqualTo(new ConnectionVersion(1));
        assertThat(found.orElseThrow().currentSecret()).isEmpty();
    }

    @Test
    void aSecretIsAttachedThroughCompareAndSetAfterTheInitialInsert() {
        var reserved = DatabaseConnection.reserve(WORKSPACE_ONE, CONNECTION, DatabaseKind.POSTGRESQL);
        REPOSITORY.insert(reserved);
        var versionReserved = reserved.reserveNextSecretVersion(new ConnectionVersion(1));
        REPOSITORY.compareAndSet(versionReserved, new ConnectionVersion(1));
        var pendingValidation =
                versionReserved.attachCurrentSecret(new ConnectionVersion(2), secret(WORKSPACE_ONE, CONNECTION, 1));
        REPOSITORY.compareAndSet(pendingValidation, new ConnectionVersion(2));

        var found = REPOSITORY.findByWorkspaceAndId(WORKSPACE_ONE, CONNECTION).orElseThrow();

        assertThat(found.state()).isEqualTo(ConnectionState.PENDING_VALIDATION);
        assertThat(found.currentSecret()).contains(secret(WORKSPACE_ONE, CONNECTION, 1));
        assertThat(found.latestSecretVersion()).isEqualTo(1);
    }

    @Test
    void insertRejectsAConnectionThatIsNotAFreshReservation() {
        var withSecret = DatabaseConnection.reserve(WORKSPACE_ONE, CONNECTION, DatabaseKind.POSTGRESQL)
                .reserveNextSecretVersion(new ConnectionVersion(1))
                .attachCurrentSecret(new ConnectionVersion(2), secret(WORKSPACE_ONE, CONNECTION, 1));

        assertThatThrownBy(() -> REPOSITORY.insert(withSecret)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aConnectionIsInvisibleToAWorkspaceThatDoesNotOwnIt() {
        REPOSITORY.insert(DatabaseConnection.reserve(WORKSPACE_ONE, CONNECTION, DatabaseKind.POSTGRESQL));

        assertThat(REPOSITORY.findByWorkspaceAndId(WORKSPACE_TWO, CONNECTION)).isEmpty();
        assertThat(REPOSITORY.findByWorkspaceAndId(WORKSPACE_ONE, CONNECTION)).isPresent();
    }

    @Test
    void compareAndSetPersistsAStateTransitionAndAdvancesTheStoredVersion() {
        var reserved = DatabaseConnection.reserve(WORKSPACE_ONE, CONNECTION, DatabaseKind.POSTGRESQL);
        REPOSITORY.insert(reserved);
        var deleting = reserved.transition(new ConnectionVersion(1), ConnectionState.DELETING);

        boolean applied = REPOSITORY.compareAndSet(deleting, new ConnectionVersion(1));

        assertThat(applied).isTrue();
        var found = REPOSITORY.findByWorkspaceAndId(WORKSPACE_ONE, CONNECTION).orElseThrow();
        assertThat(found.state()).isEqualTo(ConnectionState.DELETING);
        assertThat(found.version()).isEqualTo(new ConnectionVersion(2));
    }

    @Test
    void compareAndSetOnAnUnknownConnectionReturnsFalseRatherThanThrowing() {
        var phantom = DatabaseConnection.reserve(WORKSPACE_ONE, CONNECTION, DatabaseKind.POSTGRESQL)
                .transition(new ConnectionVersion(1), ConnectionState.DELETING);

        assertThat(REPOSITORY.compareAndSet(phantom, new ConnectionVersion(1))).isFalse();
    }

    @Test
    void compareAndSetWithAStaleExpectedVersionThrowsRatherThanOverwritingAConcurrentWriter() {
        var reserved = DatabaseConnection.reserve(WORKSPACE_ONE, CONNECTION, DatabaseKind.POSTGRESQL);
        REPOSITORY.insert(reserved);
        // Concurrent writer advances the stored row to version 2 first.
        REPOSITORY.compareAndSet(
                reserved.transition(new ConnectionVersion(1), ConnectionState.DELETING), new ConnectionVersion(1));

        var staleWriter = reserved.transition(new ConnectionVersion(1), ConnectionState.DELETING);

        assertThatThrownBy(() -> REPOSITORY.compareAndSet(staleWriter, new ConnectionVersion(1)))
                .isInstanceOf(StaleConnectionVersionException.class);
        // The concurrent writer's update must survive untouched.
        assertThat(REPOSITORY.findByWorkspaceAndId(WORKSPACE_ONE, CONNECTION).orElseThrow().version())
                .isEqualTo(new ConnectionVersion(2));
    }

    @Test
    void compareAndSetOnADeletedConnectionWithASecretReturnsFalseRatherThanThrowing() {
        var reserved = DatabaseConnection.reserve(WORKSPACE_ONE, CONNECTION, DatabaseKind.POSTGRESQL);
        REPOSITORY.insert(reserved);
        var versionReserved = reserved.reserveNextSecretVersion(new ConnectionVersion(1));
        REPOSITORY.compareAndSet(versionReserved, new ConnectionVersion(1));
        var withSecret =
                versionReserved.attachCurrentSecret(new ConnectionVersion(2), secret(WORKSPACE_ONE, CONNECTION, 1));

        // The connection row disappears out from under this in-memory
        // aggregate (e.g. a concurrent hard-delete), so this caller is left
        // holding a stale, secret-bearing DatabaseConnection instance. The
        // secret-reference insert this write would otherwise attempt has its
        // own FK into connections and must not be allowed to surface as a
        // raw FK-violation DataAccessException.
        JDBC.update(
                "DELETE FROM connections WHERE workspace_id = ? AND connection_id = ?",
                WORKSPACE_ONE.value(), CONNECTION.value());

        assertThat(REPOSITORY.compareAndSet(withSecret, new ConnectionVersion(2))).isFalse();
        Integer orphanedSecretReferences = JDBC.queryForObject(
                "SELECT COUNT(*) FROM connection_secret_references WHERE workspace_id = ? AND connection_id = ?",
                Integer.class, WORKSPACE_ONE.value(), CONNECTION.value());
        assertThat(orphanedSecretReferences).isEqualTo(0);
    }

    @Test
    void compareAndSetRollsBackTheSecretReferenceInsertWhenTheExpectedVersionIsStale() {
        var reserved = DatabaseConnection.reserve(WORKSPACE_ONE, CONNECTION, DatabaseKind.POSTGRESQL);
        REPOSITORY.insert(reserved);
        var versionReserved = reserved.reserveNextSecretVersion(new ConnectionVersion(1));
        REPOSITORY.compareAndSet(versionReserved, new ConnectionVersion(1));

        // A concurrent writer advances the stored row (to version 3) first.
        var concurrentDelete = versionReserved.transition(new ConnectionVersion(2), ConnectionState.DELETING);
        REPOSITORY.compareAndSet(concurrentDelete, new ConnectionVersion(2));

        // This writer still believes the row is at version 2 and additionally
        // carries a brand-new secret reference (version 1) that has never
        // been recorded. The insert step for that reference will succeed on
        // its own; only the CAS itself must fail (stale expectedVersion).
        // Proving atomicity means proving that "successful" insert does not
        // survive the transaction rollback that the exception below triggers
        // - this requires compareAndSet's real @Transactional proxy, not a
        // plain `new JdbcConnectionRepository(...)`.
        var staleWriterWithSecret =
                versionReserved.attachCurrentSecret(new ConnectionVersion(2), secret(WORKSPACE_ONE, CONNECTION, 1));

        assertThatThrownBy(() -> REPOSITORY.compareAndSet(staleWriterWithSecret, new ConnectionVersion(2)))
                .isInstanceOf(StaleConnectionVersionException.class);

        Integer orphanedSecretReferences = JDBC.queryForObject(
                "SELECT COUNT(*) FROM connection_secret_references WHERE workspace_id = ? AND connection_id = ?",
                Integer.class, WORKSPACE_ONE.value(), CONNECTION.value());
        assertThat(orphanedSecretReferences).isEqualTo(0);
    }

    @Test
    void compareAndSetRejectsADifferentSecretReferenceReusingAnAlreadyRecordedVersion() {
        var reserved = DatabaseConnection.reserve(WORKSPACE_ONE, CONNECTION, DatabaseKind.POSTGRESQL);
        REPOSITORY.insert(reserved);
        var versionReserved = reserved.reserveNextSecretVersion(new ConnectionVersion(1));
        REPOSITORY.compareAndSet(versionReserved, new ConnectionVersion(1));
        var withReferenceA =
                versionReserved.attachCurrentSecret(new ConnectionVersion(2), secret(WORKSPACE_ONE, CONNECTION, 1));
        assertThat(REPOSITORY.compareAndSet(withReferenceA, new ConnectionVersion(2))).isTrue();

        // A saga retry stages a second, different secret object at the same
        // already-used version 1 (e.g. re-running a "stage secret" step
        // against a fresh credential after believing the first attempt was
        // lost), and then tries to record it directly via compareAndSet -
        // bypassing DatabaseConnection.attachCurrentSecret's own
        // version-reuse guard, exactly like a low-level repository retry
        // would.
        var conflictingReference = new BoundSecretReference(
                new SecretBinding(WORKSPACE_ONE, CONNECTION, SecretPurpose.TARGET_DATABASE_CONNECTION, 1),
                new SecretReference("local", "opaqueSecretRefDIFFR1", 1, "bindingFingerptDIFFR1"));
        var withConflictingReference = DatabaseConnection.rehydrate(
                WORKSPACE_ONE,
                CONNECTION,
                DatabaseKind.POSTGRESQL,
                ConnectionState.PENDING_VALIDATION,
                new ConnectionVersion(4),
                1,
                conflictingReference,
                null);

        assertThatThrownBy(() -> REPOSITORY.compareAndSet(withConflictingReference, new ConnectionVersion(3)))
                .isInstanceOf(ConflictingSecretReferenceException.class);

        // Reference A must survive untouched, and the connection row itself
        // must not have advanced past the last successful write.
        var found = REPOSITORY.findByWorkspaceAndId(WORKSPACE_ONE, CONNECTION).orElseThrow();
        assertThat(found.currentSecret()).contains(secret(WORKSPACE_ONE, CONNECTION, 1));
        assertThat(found.version()).isEqualTo(new ConnectionVersion(3));
    }

    private static BoundSecretReference secret(WorkspaceId workspaceId, ConnectionId connectionId, long version) {
        var binding = new SecretBinding(workspaceId, connectionId, SecretPurpose.TARGET_DATABASE_CONNECTION, version);
        var reference = new SecretReference(
                "local", "opaqueSecretRef0" + version, version, "bindingFingerpt0" + version);
        return new BoundSecretReference(binding, reference);
    }
}
