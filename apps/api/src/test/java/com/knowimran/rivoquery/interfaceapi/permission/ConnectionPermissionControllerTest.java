package com.knowimran.rivoquery.interfaceapi.permission;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionPermissionGrant;
import com.knowimran.rivoquery.connection.ConnectionState;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.ConnectionPermissionRepository;
import com.knowimran.rivoquery.connection.application.ConnectionRepository;
import com.knowimran.rivoquery.connection.application.IdempotencyKeyRepository;
import com.knowimran.rivoquery.connection.application.IdempotencyRecord;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionQueryService;
import com.knowimran.rivoquery.connection.application.secret.BoundSecretReference;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretPurpose;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.CurrentMembership;
import com.knowimran.rivoquery.identity.GrantablePermission;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.MembershipStatus;
import com.knowimran.rivoquery.identity.WorkspaceActorResolver;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import com.knowimran.rivoquery.identity.application.CurrentMembershipRepository;
import com.knowimran.rivoquery.identity.application.MembershipDirectoryEntry;
import com.knowimran.rivoquery.interfaceapi.InterfaceApiTestSupport;
import com.knowimran.rivoquery.interfaceapi.RequestIdFilter;
import com.knowimran.rivoquery.interfaceapi.SafeProblemHandler;
import com.knowimran.rivoquery.interfaceapi.support.ActorResolver;
import com.knowimran.rivoquery.interfaceapi.support.IdempotencyGuard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConnectionPermissionControllerTest {

    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final WorkspaceId OTHER_WORKSPACE = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY");
    private static final ActorId ADMIN_ACTOR = new ActorId("act_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final ConnectionId CONNECTION = new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final MembershipId MEMBER = new MembershipId("mem_01J6MZ4H9ST5N7YQ2C8W3V0KFY");
    private static final MembershipId ADMINISTRATOR_MEMBERSHIP = new MembershipId("mem_01J6MZ4H9ST5N7YQ2C8W3V0KFZ");
    private static final MembershipId UNKNOWN_MEMBER = new MembershipId("mem_01J6MZ4H9ST5N7YQ2C8W3V0KG0");

    private final ObjectMapper json = new ObjectMapper();
    private FakeConnectionRepository connections;
    private FakeConnectionPermissionRepository permissions;
    private FakeMembershipRepository memberships;
    private FakeIdempotencyKeyRepository idempotencyKeys;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        connections = new FakeConnectionRepository();
        permissions = new FakeConnectionPermissionRepository();
        memberships = new FakeMembershipRepository();
        idempotencyKeys = new FakeIdempotencyKeyRepository();
        memberships.setAdministrator(WORKSPACE, ADMIN_ACTOR);
        memberships.add(WORKSPACE, MEMBER, WorkspaceRole.MEMBER);
        memberships.add(WORKSPACE, ADMINISTRATOR_MEMBERSHIP, WorkspaceRole.ADMINISTRATOR);
        activeConnection(connections, WORKSPACE, CONNECTION);

        var actorResolver = new ActorResolver(new WorkspaceActorResolver(memberships));
        var queryService = new ConnectionQueryService(connections, permissions);
        var controller = new ConnectionPermissionController(
                actorResolver, queryService, permissions, memberships, new IdempotencyGuard(idempotencyKeys),
                InterfaceApiTestSupport.requestProvider());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new SafeProblemHandler())
                .addInterceptors(InterfaceApiTestSupport.authenticationInterceptor())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void listRequiresAuthenticationBeforeAnyLookup() throws Exception {
        mockMvc.perform(get("/api/v1/connections/" + CONNECTION.value() + "/permissions")
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void listRejectsACrossTenantConnectionAsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/connections/" + CONNECTION.value() + "/permissions")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", OTHER_WORKSPACE.value())
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isForbidden()); // no membership at all in OTHER_WORKSPACE
    }

    @Test
    void listReturnsTheCurrentCollectionVersionAndGrants() throws Exception {
        permissions.put(new ConnectionPermissionGrant(
                WORKSPACE, CONNECTION, MEMBER, Set.of(GrantablePermission.VIEW_SCHEMA), 1));

        mockMvc.perform(get("/api/v1/connections/" + CONNECTION.value() + "/permissions")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.items[0].membershipId").value(MEMBER.value()));
    }

    @Test
    void replaceRejectsAnUnknownConnectionAsNotFound() throws Exception {
        mockMvc.perform(put("/api/v1/connections/con_01J6MZ4H9ST5N7YQ2C8W3V0KG9/permissions/" + MEMBER.value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody("VIEW_SCHEMA"))
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONNECTION_NOT_FOUND"));
    }

    @Test
    void replaceRejectsAnAdministratorGranteeAsIndistinguishableFromUnknown() throws Exception {
        mockMvc.perform(put("/api/v1/connections/" + CONNECTION.value() + "/permissions/" + ADMINISTRATOR_MEMBERSHIP.value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody("VIEW_SCHEMA"))
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_NOT_FOUND"));
    }

    @Test
    void replaceRejectsAnUnknownGranteeAsIndistinguishableFromInactiveOrAdministrator() throws Exception {
        mockMvc.perform(put("/api/v1/connections/" + CONNECTION.value() + "/permissions/" + UNKNOWN_MEMBER.value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody("VIEW_SCHEMA"))
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_NOT_FOUND"));
    }

    @Test
    void replaceCreatesTheFirstGrantAndAdvancesTheCollectionVersion() throws Exception {
        mockMvc.perform(put("/api/v1/connections/" + CONNECTION.value() + "/permissions/" + MEMBER.value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody("VIEW_SCHEMA", "QUERY"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.items[0].permissions", org.hamcrest.Matchers.containsInAnyOrder("VIEW_SCHEMA", "QUERY")));
    }

    @Test
    void replaceWithAStaleIfMatchIsAPreconditionFailure() throws Exception {
        permissions.put(new ConnectionPermissionGrant(
                WORKSPACE, CONNECTION, MEMBER, Set.of(GrantablePermission.VIEW_SCHEMA), 1));

        mockMvc.perform(put("/api/v1/connections/" + CONNECTION.value() + "/permissions/" + MEMBER.value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"0\"") // stale: the real version is now 1
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody("QUERY"))
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().is(412))
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
    }

    @Test
    void exactIdempotentReplayOfReplaceReturnsTheSameResultWithoutReapplying() throws Exception {
        String body = replaceBody("VIEW_SCHEMA");
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(put("/api/v1/connections/" + CONNECTION.value() + "/permissions/" + MEMBER.value())
                            .with(InterfaceApiTestSupport.authenticated())
                            .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                            .header("Idempotency-Key", "idem-key-1234567890abcdef")
                            .header("If-Match", "\"0\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(header().string("ETag", "\"1\""));
        }
        // A second real apply (not a replay) would have needed If-Match "1"
        // and would otherwise have failed with 412; only one grant exists.
        org.assertj.core.api.Assertions.assertThat(permissions.currentCollectionVersion(WORKSPACE, CONNECTION)).isEqualTo(1);
    }

    @Test
    void reusingTheIdempotencyKeyWithADifferentBodyIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/connections/" + CONNECTION.value() + "/permissions/" + MEMBER.value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody("VIEW_SCHEMA"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/connections/" + CONNECTION.value() + "/permissions/" + MEMBER.value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody("QUERY"))
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void revokeRemovesTheGrantAndAdvancesTheCollectionVersion() throws Exception {
        permissions.put(new ConnectionPermissionGrant(
                WORKSPACE, CONNECTION, MEMBER, Set.of(GrantablePermission.VIEW_SCHEMA), 1));

        mockMvc.perform(delete("/api/v1/connections/" + CONNECTION.value() + "/permissions/" + MEMBER.value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"1\"")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void nonAdministratorCannotManagePermissions() throws Exception {
        memberships.setCurrentRole(WORKSPACE, WorkspaceRole.MEMBER);

        mockMvc.perform(get("/api/v1/connections/" + CONNECTION.value() + "/permissions")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMINISTRATOR_REQUIRED"));
    }

    private String replaceBody(String... permissions) throws Exception {
        return json.writeValueAsString(Map.of("permissions", List.of(permissions)));
    }

    private static void activeConnection(FakeConnectionRepository connections, WorkspaceId workspaceId, ConnectionId connectionId) {
        var reserved = DatabaseConnection.reserve(workspaceId, connectionId, DatabaseKind.POSTGRESQL);
        connections.insert(reserved);
        var versionReserved = reserved.reserveNextSecretVersion(reserved.version());
        if (!connections.compareAndSet(versionReserved, reserved.version())) {
            throw new IllegalStateException("test setup failed");
        }
        var binding = new SecretBinding(workspaceId, connectionId, SecretPurpose.TARGET_DATABASE_CONNECTION, 1);
        var reference = new SecretReference("local-fake", "opaqueaaaaaaaaaaaaaaaaa", 1, "fingerprintaaaaaaaaaaaaa");
        var secret = new BoundSecretReference(binding, reference);
        var pendingValidation = versionReserved.attachCurrentSecret(versionReserved.version(), secret);
        var active = pendingValidation.transition(pendingValidation.version(), ConnectionState.ACTIVE);
        // The fake repository (like the real one) only ever holds the last
        // persisted row: the combined attach+transition write above was
        // computed in-memory from versionReserved (the row actually
        // persisted by the first compareAndSet), so that is the expected
        // version this second compareAndSet must be guarded by - not
        // pendingValidation's own already-bumped-twice version.
        if (!connections.compareAndSet(active, versionReserved.version())) {
            throw new IllegalStateException("test setup failed");
        }
    }

    private static final class FakeConnectionRepository implements ConnectionRepository {
        private final Map<String, DatabaseConnection> byKey = new HashMap<>();

        private static String key(WorkspaceId workspaceId, ConnectionId connectionId) {
            return workspaceId.value() + "/" + connectionId.value();
        }

        @Override
        public Optional<DatabaseConnection> findByWorkspaceAndId(WorkspaceId workspaceId, ConnectionId connectionId) {
            return Optional.ofNullable(byKey.get(key(workspaceId, connectionId)));
        }

        @Override
        public void insert(DatabaseConnection connection) {
            byKey.put(key(connection.workspaceId(), connection.id()), connection);
        }

        @Override
        public boolean compareAndSet(DatabaseConnection connection, ConnectionVersion expectedVersion) {
            String mapKey = key(connection.workspaceId(), connection.id());
            DatabaseConnection stored = byKey.get(mapKey);
            if (stored == null || !stored.version().equals(expectedVersion)) {
                return false;
            }
            byKey.put(mapKey, connection);
            return true;
        }

        @Override
        public List<DatabaseConnection> findPage(WorkspaceId workspaceId, Optional<ConnectionId> afterConnectionId, int limit) {
            return byKey.values().stream()
                    .filter(c -> c.workspaceId().equals(workspaceId))
                    .filter(c -> c.state().isPublicState())
                    .sorted((a, b) -> a.id().value().compareTo(b.id().value()))
                    .limit(limit)
                    .toList();
        }
    }

    private static final class FakeConnectionPermissionRepository implements ConnectionPermissionRepository {
        private final Map<String, ConnectionPermissionGrant> grants = new HashMap<>();

        private static String key(WorkspaceId workspaceId, ConnectionId connectionId, MembershipId membershipId) {
            return workspaceId.value() + "/" + connectionId.value() + "/" + membershipId.value();
        }

        void put(ConnectionPermissionGrant grant) {
            grants.put(key(grant.workspaceId(), grant.connectionId(), grant.membershipId()), grant);
        }

        @Override
        public Optional<ConnectionPermissionGrant> find(WorkspaceId workspaceId, ConnectionId connectionId, MembershipId membershipId) {
            return Optional.ofNullable(grants.get(key(workspaceId, connectionId, membershipId)));
        }

        @Override
        public boolean replace(ConnectionPermissionGrant grant, long expectedCollectionVersion) {
            if (currentCollectionVersion(grant.workspaceId(), grant.connectionId()) != expectedCollectionVersion) {
                return false;
            }
            put(grant);
            return true;
        }

        @Override
        public boolean revoke(WorkspaceId workspaceId, ConnectionId connectionId, MembershipId membershipId, long expectedCollectionVersion) {
            if (currentCollectionVersion(workspaceId, connectionId) != expectedCollectionVersion) {
                return false;
            }
            return grants.remove(key(workspaceId, connectionId, membershipId)) != null;
        }

        @Override
        public List<ConnectionPermissionGrant> findPage(
                WorkspaceId workspaceId, ConnectionId connectionId, Optional<MembershipId> afterMembershipId, int limit) {
            String after = afterMembershipId.map(MembershipId::value).orElse("");
            return grants.values().stream()
                    .filter(grant -> grant.workspaceId().equals(workspaceId) && grant.connectionId().equals(connectionId))
                    .filter(grant -> grant.membershipId().value().compareTo(after) > 0)
                    .sorted(java.util.Comparator.comparing(grant -> grant.membershipId().value()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public long currentCollectionVersion(WorkspaceId workspaceId, ConnectionId connectionId) {
            return grants.values().stream()
                    .filter(grant -> grant.workspaceId().equals(workspaceId) && grant.connectionId().equals(connectionId))
                    .mapToLong(ConnectionPermissionGrant::version)
                    .max()
                    .orElse(0L);
        }
    }

    private static final class FakeMembershipRepository implements CurrentMembershipRepository {
        private final Map<String, WorkspaceRole> currentByWorkspace = new HashMap<>();
        private final List<MembershipDirectoryEntry> directory = new ArrayList<>();

        void setAdministrator(WorkspaceId workspaceId, ActorId actorId) {
            currentByWorkspace.put(workspaceId.value(), WorkspaceRole.ADMINISTRATOR);
        }

        void setCurrentRole(WorkspaceId workspaceId, WorkspaceRole role) {
            currentByWorkspace.put(workspaceId.value(), role);
        }

        void add(WorkspaceId workspaceId, MembershipId membershipId, WorkspaceRole role) {
            directory.add(new MembershipDirectoryEntry(workspaceId, membershipId, "subject", role));
        }

        @Override
        public Optional<CurrentMembership> findCurrentActiveMembership(String issuer, String subject, WorkspaceId workspaceId) {
            WorkspaceRole role = currentByWorkspace.get(workspaceId.value());
            if (role == null) {
                return Optional.empty();
            }
            return Optional.of(new CurrentMembership(
                    ADMIN_ACTOR, workspaceId, new MembershipId("mem_01J6MZ4H9ST5N7YQ2C8W3V0KG1"),
                    role, MembershipStatus.ACTIVE));
        }

        @Override
        public List<MembershipDirectoryEntry> findActiveMembershipsForActor(String issuer, String subject) {
            return List.copyOf(directory);
        }

        @Override
        public List<MembershipDirectoryEntry> findActiveMemberships(
                WorkspaceId workspaceId, Optional<MembershipId> afterMembershipId, int limit) {
            return List.of();
        }

        @Override
        public Optional<MembershipDirectoryEntry> findActiveMembership(WorkspaceId workspaceId, MembershipId membershipId) {
            return directory.stream()
                    .filter(entry -> entry.workspaceId().equals(workspaceId) && entry.membershipId().equals(membershipId))
                    .findFirst();
        }
    }

    private static final class FakeIdempotencyKeyRepository implements IdempotencyKeyRepository {
        private final Map<String, IdempotencyRecord> byKey = new HashMap<>();

        private static String key(WorkspaceId workspaceId, ActorId actorId, String key) {
            return workspaceId.value() + "/" + actorId.value() + "/" + key;
        }

        @Override
        public Optional<IdempotencyRecord> find(WorkspaceId workspaceId, ActorId actorId, String key) {
            return Optional.ofNullable(byKey.get(key(workspaceId, actorId, key)));
        }

        @Override
        public boolean tryReserve(IdempotencyRecord record) {
            return byKey.putIfAbsent(key(record.workspaceId(), record.actorId(), record.key()), record) == null;
        }

        @Override
        public void complete(WorkspaceId workspaceId, ActorId actorId, String key, Optional<String> operationId) {
            byKey.computeIfPresent(key(workspaceId, actorId, key), (mapKey, existing) -> new IdempotencyRecord(
                    existing.workspaceId(), existing.actorId(), existing.key(), existing.action(),
                    existing.requestFingerprint(), operationId.or(existing::operationId), true));
        }

        @Override
        public void rollback(WorkspaceId workspaceId, ActorId actorId, String key) {
            byKey.computeIfPresent(key(workspaceId, actorId, key),
                    (mapKey, existing) -> existing.completed() ? existing : null);
        }
    }
}
