package com.knowimran.rivoquery.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.RivoQueryApplication;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionPermissionGrant;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.connection.application.AuditRequestId;
import com.knowimran.rivoquery.connection.application.ConnectionAuditAction;
import com.knowimran.rivoquery.connection.application.ConnectionAuditCategory;
import com.knowimran.rivoquery.connection.application.ConnectionAuditOutcome;
import com.knowimran.rivoquery.connection.application.ConnectionAuditPort;
import com.knowimran.rivoquery.connection.application.DurableOperation;
import com.knowimran.rivoquery.connection.application.IllegalDurableOperationTransitionException;
import com.knowimran.rivoquery.connection.application.OperationAction;
import com.knowimran.rivoquery.connection.application.OperationState;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.GrantablePermission;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.MembershipStatus;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-PostgreSQL coverage for the remaining P2-060 repository adapters:
 * membership resolution, connection permission grants (including the shared
 * optimistic collection version), durable operations, and the audit port.
 *
 * <p>Every test builds its own uniquely-suffixed workspace/connection/actor
 * fixtures rather than sharing state reset between tests, because the
 * {@code connection_lifecycle_audit_events} table genuinely has no
 * delete path (see {@link ControlPlaneSchemaConstraintsIT}): once a test
 * writes an audit row against a connection, that connection can never be
 * deleted again, so a shared-state "wipe tables between tests" strategy is
 * incompatible with the append-only guarantee this migration provides.
 */
@Testcontainers
class IdentityAndConnectionPersistenceIT {

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
    private static JdbcCurrentMembershipRepository MEMBERSHIPS;
    private static JdbcConnectionPermissionRepository PERMISSIONS;
    private static JdbcDurableOperationRepository OPERATIONS;
    private static JdbcConnectionAuditRepository AUDIT;
    private static JdbcConnectionRepository CONNECTIONS;

    private static final AtomicLong SEQUENCE = new AtomicLong();

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
        // on PERMISSIONS.replace and OPERATIONS.save is backed by a real AOP
        // proxy; see the rollback tests below, which fail without it.
        MEMBERSHIPS = CONTEXT.getBean(JdbcCurrentMembershipRepository.class);
        PERMISSIONS = CONTEXT.getBean(JdbcConnectionPermissionRepository.class);
        OPERATIONS = CONTEXT.getBean(JdbcDurableOperationRepository.class);
        AUDIT = CONTEXT.getBean(JdbcConnectionAuditRepository.class);
        CONNECTIONS = CONTEXT.getBean(JdbcConnectionRepository.class);
    }

    @AfterAll
    static void shutdown() {
        if (CONTEXT != null) {
            CONTEXT.close();
        }
    }

    @Test
    void membershipIsResolvedByIssuerAndSubjectScopedToTheSelectedWorkspace() {
        WorkspaceId workspace = insertWorkspace();
        String subject = "subject-1-" + SEQUENCE.incrementAndGet();
        ActorId actor = insertActor("issuer-1", subject);
        MembershipId membership = insertMembership(workspace, actor, WorkspaceRole.MEMBER);

        Optional<com.knowimran.rivoquery.identity.CurrentMembership> found =
                MEMBERSHIPS.findCurrentActiveMembership("issuer-1", subject, workspace);

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().membershipId()).isEqualTo(membership);
        assertThat(found.orElseThrow().role()).isEqualTo(WorkspaceRole.MEMBER);
        assertThat(found.orElseThrow().status()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    void membershipLookupIsScopedToTheRequestedWorkspaceOnly() {
        WorkspaceId workspaceOne = insertWorkspace();
        WorkspaceId workspaceTwo = insertWorkspace();
        String subject = "subject-2-" + SEQUENCE.incrementAndGet();
        ActorId actor = insertActor("issuer-2", subject);
        insertMembership(workspaceOne, actor, WorkspaceRole.MEMBER);

        assertThat(MEMBERSHIPS.findCurrentActiveMembership("issuer-2", subject, workspaceTwo)).isEmpty();
        assertThat(MEMBERSHIPS.findCurrentActiveMembership("unknown-issuer", subject, workspaceOne)).isEmpty();
    }

    @Test
    void findCurrentActiveMembershipNeverReturnsAnInactiveMembership() {
        WorkspaceId workspace = insertWorkspace();
        String subject = "subject-inactive-" + SEQUENCE.incrementAndGet();
        ActorId actor = insertActor("issuer-inactive", subject);
        JDBC.update(
                "INSERT INTO memberships (workspace_id, membership_id, actor_id, role, status) "
                        + "VALUES (?, ?, ?, 'MEMBER', 'INACTIVE')",
                workspace.value(), "mem_" + ulid(), actor.value());

        assertThat(MEMBERSHIPS.findCurrentActiveMembership("issuer-inactive", subject, workspace)).isEmpty();
    }

    @Test
    void firstPermissionGrantBootstrapsTheCollectionVersionAtOne() {
        WorkspaceId workspace = insertWorkspace();
        ConnectionId connection = insertConnection(workspace);
        MembershipId membership = insertMembership(workspace, insertActor("issuer-p1", "subject-p1"), WorkspaceRole.MEMBER);
        var grant = new ConnectionPermissionGrant(
                workspace, connection, membership, Set.of(GrantablePermission.VIEW_SCHEMA), 1L);

        assertThat(PERMISSIONS.replace(grant, 0L)).isTrue();

        var found = PERMISSIONS.find(workspace, connection, membership).orElseThrow();
        assertThat(found.permissions()).containsExactly(GrantablePermission.VIEW_SCHEMA);
        assertThat(found.version()).isEqualTo(1L);
    }

    @Test
    void replaceWithAStaleCollectionVersionIsRejected() {
        WorkspaceId workspace = insertWorkspace();
        ConnectionId connection = insertConnection(workspace);
        MembershipId membership = insertMembership(workspace, insertActor("issuer-p2", "subject-p2"), WorkspaceRole.MEMBER);
        var first = new ConnectionPermissionGrant(
                workspace, connection, membership, Set.of(GrantablePermission.VIEW_SCHEMA), 1L);
        assertThat(PERMISSIONS.replace(first, 0L)).isTrue();

        // A caller that still believes the collection is at version 0 (stale
        // read) constructs its own next version (1) consistently, but the
        // collection is actually already at 1, so the CAS must fail.
        var staleAttempt = new ConnectionPermissionGrant(
                workspace, connection, membership, Set.of(GrantablePermission.QUERY), 1L);
        assertThat(PERMISSIONS.replace(staleAttempt, 0L)).isFalse();

        // The original grant must survive untouched.
        assertThat(PERMISSIONS.find(workspace, connection, membership).orElseThrow().permissions())
                .containsExactly(GrantablePermission.VIEW_SCHEMA);
    }

    @Test
    void replaceRollsBackTheCollectionVersionBootstrapWhenTheGrantInsertFails() {
        WorkspaceId workspace = insertWorkspace();
        ConnectionId connection = insertConnection(workspace);
        // A membership id that was never inserted: the grant upsert's own FK
        // into memberships must fail, but only after casCollectionVersion has
        // already durably inserted the collection_permission_collections row
        // for this (workspace, connection) at version 1. Proving that first
        // insert does not survive requires replace()'s real @Transactional
        // proxy, not a plain `new JdbcConnectionPermissionRepository(...)`.
        MembershipId unknownMembership = new MembershipId("mem_" + ulid());
        var grant = new ConnectionPermissionGrant(
                workspace, connection, unknownMembership, Set.of(GrantablePermission.VIEW_SCHEMA), 1L);

        assertThatThrownBy(() -> PERMISSIONS.replace(grant, 0L)).isInstanceOf(DataAccessException.class);

        Integer collectionRows = JDBC.queryForObject(
                "SELECT COUNT(*) FROM connection_permission_collections WHERE workspace_id = ? AND connection_id = ?",
                Integer.class, workspace.value(), connection.value());
        assertThat(collectionRows).isEqualTo(0);
    }

    @Test
    void revokeAdvancesTheCollectionVersionAndRemovesTheGrant() {
        WorkspaceId workspace = insertWorkspace();
        ConnectionId connection = insertConnection(workspace);
        MembershipId membership = insertMembership(workspace, insertActor("issuer-p3", "subject-p3"), WorkspaceRole.MEMBER);
        var grant = new ConnectionPermissionGrant(
                workspace, connection, membership, Set.of(GrantablePermission.EXPORT), 1L);
        assertThat(PERMISSIONS.replace(grant, 0L)).isTrue();

        assertThat(PERMISSIONS.revoke(workspace, connection, membership, 1L)).isTrue();
        assertThat(PERMISSIONS.find(workspace, connection, membership)).isEmpty();

        // revoke() advanced the collection version from 1 to 2, so the next
        // write must be expected-at-2, next-value-3.
        var regranted = new ConnectionPermissionGrant(
                workspace, connection, membership, Set.of(GrantablePermission.EXPORT), 3L);
        assertThat(PERMISSIONS.replace(regranted, 2L)).isTrue();
    }

    @Test
    void durableOperationSaveIsAnIdempotentUpsertScopedByWorkspace() {
        WorkspaceId workspaceOne = insertWorkspace();
        WorkspaceId workspaceTwo = insertWorkspace();
        ConnectionId connection = insertConnection(workspaceOne);
        ActorId actor = insertActor("issuer-op", "subject-op-" + SEQUENCE.incrementAndGet());
        var operation = new DurableOperation(
                newOperationId(),
                workspaceOne,
                actor,
                Optional.of(connection),
                OperationAction.CONNECTION_CREATE,
                OperationState.PENDING,
                false);
        OPERATIONS.save(operation);

        var running = new DurableOperation(
                operation.id(), workspaceOne, actor, Optional.of(connection),
                OperationAction.CONNECTION_CREATE, OperationState.RUNNING, false);
        OPERATIONS.save(running);

        var found = OPERATIONS.findByWorkspaceAndId(workspaceOne, operation.id()).orElseThrow();
        assertThat(found.state()).isEqualTo(OperationState.RUNNING);
        assertThat(found.connectionId()).contains(connection);
        assertThat(OPERATIONS.findByWorkspaceAndId(workspaceTwo, operation.id())).isEmpty();
    }

    @Test
    void durableOperationSaveRejectsRewritingATerminalOperationBackToPending() {
        WorkspaceId workspace = insertWorkspace();
        ConnectionId connection = insertConnection(workspace);
        ActorId actor = insertActor("issuer-op-term", "subject-op-term-" + SEQUENCE.incrementAndGet());
        var succeeded = new DurableOperation(
                newOperationId(),
                workspace,
                actor,
                Optional.of(connection),
                OperationAction.CONNECTION_CREATE,
                OperationState.SUCCEEDED,
                false);
        OPERATIONS.save(succeeded);

        var rewound = new DurableOperation(
                succeeded.id(),
                workspace,
                actor,
                Optional.of(connection),
                OperationAction.CONNECTION_DELETE,
                OperationState.PENDING,
                false);

        assertThatThrownBy(() -> OPERATIONS.save(rewound))
                .isInstanceOf(IllegalDurableOperationTransitionException.class);

        // The terminal row must survive untouched.
        var found = OPERATIONS.findByWorkspaceAndId(workspace, succeeded.id()).orElseThrow();
        assertThat(found.state()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(found.action()).isEqualTo(OperationAction.CONNECTION_CREATE);

        // Re-saving the exact same terminal row is still a legitimate
        // idempotent retry and must not throw.
        OPERATIONS.save(succeeded);
    }

    @Test
    void auditAppendPersistsOnlySafeMetadataAndIsVisibleOnlyForItsOwnConnection() {
        WorkspaceId workspace = insertWorkspace();
        ConnectionId connection = insertConnection(workspace);
        ActorId actor = insertActor("issuer-audit", "subject-audit-" + SEQUENCE.incrementAndGet());

        AUDIT.append(new ConnectionAuditPort.Event(
                workspace,
                actor,
                connection,
                new AuditRequestId("req-" + SEQUENCE.incrementAndGet()),
                ConnectionAuditAction.CREATE,
                ConnectionAuditOutcome.SUCCEEDED,
                ConnectionAuditCategory.AUTHORIZATION,
                Instant.parse("2026-08-23T00:00:00Z")));

        Integer count = JDBC.queryForObject(
                "SELECT COUNT(*) FROM connection_lifecycle_audit_events WHERE workspace_id = ? AND connection_id = ?",
                Integer.class, workspace.value(), connection.value());
        assertThat(count).isEqualTo(1);

        Integer columnCount = JDBC.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'connection_lifecycle_audit_events'",
                Integer.class);
        // workspace_id, actor_id, connection_id, request_id, action, outcome, category,
        // occurred_at, recorded_at, id -- never a secret/credential/raw-driver column.
        assertThat(columnCount).isEqualTo(10);
    }

    private static WorkspaceId insertWorkspace() {
        WorkspaceId id = new WorkspaceId("wsp_" + ulid());
        JDBC.update("INSERT INTO workspaces (workspace_id, display_name) VALUES (?, ?)", id.value(), "ws-" + id.value());
        return id;
    }

    private static ActorId insertActor(String issuer, String subject) {
        ActorId id = new ActorId("act_" + ulid());
        JDBC.update("INSERT INTO actors (actor_id, issuer, subject) VALUES (?, ?, ?)", id.value(), issuer, subject);
        return id;
    }

    private static MembershipId insertMembership(WorkspaceId workspaceId, ActorId actorId, WorkspaceRole role) {
        MembershipId id = new MembershipId("mem_" + ulid());
        JDBC.update(
                "INSERT INTO memberships (workspace_id, membership_id, actor_id, role, status) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE')",
                workspaceId.value(), id.value(), actorId.value(), role.name());
        return id;
    }

    private static ConnectionId insertConnection(WorkspaceId workspaceId) {
        ConnectionId id = new ConnectionId("con_" + ulid());
        CONNECTIONS.insert(DatabaseConnection.reserve(workspaceId, id, DatabaseKind.POSTGRESQL));
        return id;
    }

    private static OperationId newOperationId() {
        return new OperationId("op_" + ulid());
    }

    private static String ulid() {
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        long value = SEQUENCE.incrementAndGet();
        char[] chars = new char[26];
        for (int i = 25; i >= 0; i--) {
            chars[i] = alphabet.charAt((int) (value % alphabet.length()));
            value /= alphabet.length();
        }
        return new String(chars);
    }
}
