package com.knowimran.rivoquery.connection.application.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowimran.rivoquery.RivoQueryApplication;
import com.knowimran.rivoquery.connection.ConnectionState;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.AuditRequestId;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.OperationAction;
import com.knowimran.rivoquery.connection.application.OperationState;
import com.knowimran.rivoquery.connection.application.destination.AddressFamily;
import com.knowimran.rivoquery.connection.application.destination.DestinationAssessment;
import com.knowimran.rivoquery.connection.application.destination.StructuredEndpoint;
import com.knowimran.rivoquery.connection.application.destination.VettedAddress;
import com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.platform.persistence.ControlDatabaseRoleFixture;
import com.knowimran.rivoquery.platform.persistence.JdbcConnectionAuditRepository;
import com.knowimran.rivoquery.platform.persistence.JdbcConnectionRepository;
import com.knowimran.rivoquery.platform.persistence.JdbcDurableOperationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * Real-PostgreSQL coverage for {@link ConnectionLifecycleService#create}
 * driving the actual migrated schema through the real {@link
 * JdbcConnectionRepository}, {@link JdbcDurableOperationRepository}, and
 * {@link JdbcConnectionAuditRepository} adapters - never the in-memory fakes
 * {@link ConnectionLifecycleServiceTest} otherwise uses for those three.
 *
 * <p>This exists specifically to catch a class of bug the in-memory fakes
 * structurally cannot: {@code durable_operations} carries a non-deferrable
 * composite foreign key {@code (workspace_id, connection_id) REFERENCES
 * connections (workspace_id, connection_id)}, but {@code
 * LifecycleTestFixtures.FakeDurableOperationRepository} is a bare {@code
 * HashMap} with no such constraint. {@code create()} previously saved the
 * first {@link com.knowimran.rivoquery.connection.application.DurableOperation}
 * (which references the brand-new {@code connectionId}) before {@code
 * connections.insert(...)} had ever created that connection row, which is
 * silently accepted by the fake and a hard {@code DataIntegrityViolationException}
 * against real PostgreSQL. Only the secret store, destination policy, and
 * connector are faked here (per {@code LifecycleTestFixtures}, none of those
 * three adapters has a production implementation reviewed for this task, and
 * none of them is where the FK in question lives).
 */
@Testcontainers
class ConnectionLifecycleServiceCreateIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:17.6-alpine3.22@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    private static final String RUNTIME_APP_USERNAME = "rivoquery_app_it";
    private static final String RUNTIME_APP_PASSWORD = "rivoquery-app-it-synthetic-pw";

    private static ConfigurableApplicationContext CONTEXT;
    private static JdbcTemplate JDBC;
    private static JdbcConnectionRepository CONNECTIONS;
    private static JdbcDurableOperationRepository OPERATIONS;
    private static JdbcConnectionAuditRepository AUDIT;

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String ADMIN_ULID = "01J9QK3H0RT8N4YB2C6W9V1MFP";
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_" + ADMIN_ULID);
    private static final ActorId ADMIN_ACTOR = new ActorId("act_" + ADMIN_ULID);
    private static final StructuredEndpoint ENDPOINT = new StructuredEndpoint("db.example.com", 5432, "app_db");

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
        // Fetched from the Spring context (not `new`) so @Transactional on
        // compareAndSet/save is backed by a real AOP proxy, matching
        // ConnectionRepositoryPersistenceIT's own rationale.
        CONNECTIONS = CONTEXT.getBean(JdbcConnectionRepository.class);
        OPERATIONS = CONTEXT.getBean(JdbcDurableOperationRepository.class);
        AUDIT = CONTEXT.getBean(JdbcConnectionAuditRepository.class);
    }

    @AfterAll
    static void shutdown() {
        if (CONTEXT != null) {
            CONTEXT.close();
        }
    }

    @BeforeEach
    void resetData() {
        JDBC.update("DELETE FROM idempotency_keys");
        JDBC.update("DELETE FROM durable_operations");
        JDBC.update("DELETE FROM connection_permission_grants");
        JDBC.update("DELETE FROM connection_permission_collections");
        JDBC.update(
                "UPDATE connections SET state = 'PENDING_SECRET', current_secret_version = NULL, "
                        + "secret_retire_at = NULL");
        JDBC.update("DELETE FROM connection_secret_references");
        JDBC.update("DELETE FROM connections");
        JDBC.update("DELETE FROM memberships");
        JDBC.update("DELETE FROM actors");
        JDBC.update("DELETE FROM workspaces");
        JDBC.update("INSERT INTO workspaces (workspace_id, display_name) VALUES (?, 'One')", WORKSPACE.value());
        JDBC.update(
                "INSERT INTO actors (actor_id, issuer, subject) VALUES (?, 'issuer', 'subject')",
                ADMIN_ACTOR.value());
    }

    @Test
    void createPersistsTheConnectionAndItsDurableOperationAgainstRealPostgresWithoutAForeignKeyViolation() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var secretAuthorizations = new SecretOperationAuthorizationRegistry(clock, Duration.ofMinutes(5));
        var secretStore = new LifecycleTestFixtures.FakeSecretStore();
        var secretOperations = LifecycleTestFixtures.secretOperations(CONNECTIONS, secretAuthorizations, secretStore, clock);
        var destinationPolicy = new LifecycleTestFixtures.FakeDestinationPolicy();
        destinationPolicy.nextAssessment = new DestinationAssessment(
                "db.example.com", "db.example.com", 5432,
                Set.of(new VettedAddress(AddressFamily.IPV4, new byte[] {10, 0, 0, 1})),
                "fingerprint-v1", "policy-v1", NOW, NOW.plusSeconds(300));
        var destinationOperations = LifecycleTestFixtures.destinationOperations(destinationPolicy, clock);
        var connector = new LifecycleTestFixtures.FakeDatabaseConnector();

        ConnectionLifecycleService service = new ConnectionLifecycleService(
                CONNECTIONS, OPERATIONS, AUDIT, secretOperations, secretAuthorizations,
                destinationOperations, () -> "policy-v1",
                new LifecycleTestFixtures.SingleConnectorSelector(connector), new NoOpTargetPoolManager(),
                new InMemoryDiscoveryEnqueuer(), new SecureRandomCanonicalIdGenerator(), clock);

        ActorContext admin = LifecycleTestFixtures.administrator(WORKSPACE, ADMIN_ACTOR);
        var command = new ConnectionLifecycleService.CreateConnectionCommand(
                admin, DatabaseKind.POSTGRESQL, ENDPOINT,
                new AuditRequestId("req-" + UUID.randomUUID()), new Deadline(NOW.plusSeconds(60)));

        var result = service.create(command, new DestructibleSecretMaterial("integration-test-secret".getBytes()));

        assertThat(result.connection().state()).isEqualTo(ConnectionState.ACTIVE);
        assertThat(result.operation()).isPresent();
        assertThat(result.operation().orElseThrow().state()).isEqualTo(OperationState.SUCCEEDED);

        // Independently re-read through the real repositories (a fresh
        // SELECT, not the in-memory result the saga already returned) to
        // prove both rows actually landed in PostgreSQL, satisfying the FK
        // rather than merely not throwing.
        var persistedConnection = CONNECTIONS.findByWorkspaceAndId(WORKSPACE, result.connection().id());
        assertThat(persistedConnection).isPresent();
        assertThat(persistedConnection.orElseThrow().state()).isEqualTo(ConnectionState.ACTIVE);

        var persistedOperation = OPERATIONS.findByWorkspaceAndId(
                WORKSPACE, result.operation().orElseThrow().id());
        assertThat(persistedOperation).isPresent();
        assertThat(persistedOperation.orElseThrow().action()).isEqualTo(OperationAction.CONNECTION_CREATE);
        assertThat(persistedOperation.orElseThrow().state()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(persistedOperation.orElseThrow().connectionId()).contains(result.connection().id());

        Integer durableOperationRows = JDBC.queryForObject(
                "SELECT COUNT(*) FROM durable_operations WHERE workspace_id = ? AND connection_id = ?",
                Integer.class, WORKSPACE.value(), result.connection().id().value());
        assertThat(durableOperationRows).isEqualTo(1);
    }
}
