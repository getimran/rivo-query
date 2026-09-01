package com.knowimran.rivoquery.interfaceapi.query;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowimran.rivoquery.catalog.CanonicalQualifiedName;
import com.knowimran.rivoquery.catalog.application.CatalogRepository;
import com.knowimran.rivoquery.catalog.application.SchemaQueryService;
import com.knowimran.rivoquery.catalog.application.SchemaSelectorService;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionPermissionGrant;
import com.knowimran.rivoquery.connection.application.ConnectionPermissionRepository;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.generation.AmbiguityStatus;
import com.knowimran.rivoquery.generation.GenerationResponse;
import com.knowimran.rivoquery.generation.SqlGenerationModel;
import com.knowimran.rivoquery.generation.SqlProposal;
import com.knowimran.rivoquery.generation.application.GenerationService;
import com.knowimran.rivoquery.generation.application.GenerationService.GenerationResult;
import com.knowimran.rivoquery.identity.ActorContext;
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
import com.knowimran.rivoquery.interfaceapi.support.WorkspaceHeaderResolver;
import com.knowimran.rivoquery.querypolicy.ApprovedQuery;
import com.knowimran.rivoquery.connection.application.lifecycle.CanonicalIdGenerator;
import com.knowimran.rivoquery.connection.application.lifecycle.SecureRandomCanonicalIdGenerator;
import com.knowimran.rivoquery.querypolicy.ApprovedQuery;
import com.knowimran.rivoquery.querypolicy.JsqlParserSqlParser;
import com.knowimran.rivoquery.querypolicy.QuerySentry;
import com.knowimran.rivoquery.connection.QueryRequestId;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QueryControllerTest {

    static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    static final ConnectionId CONNECTION = new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFY");
    static final ActorId ACTOR = new ActorId("act_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    static final MembershipId MEMBERSHIP = new MembershipId("mem_01J6MZ4H9ST5N7YQ2C8W3V0KFZ");

    private FakeMembershipRepository memberships;
    private FakeConnectionPermissionRepository permissions;
    private StubGenerationService generationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        memberships = new FakeMembershipRepository();
        permissions = new FakeConnectionPermissionRepository();
        generationService = new StubGenerationService();
        var actorResolver = new ActorResolver(new WorkspaceActorResolver(memberships));
        mockMvc = MockMvcBuilders.standaloneSetup(new QueryController(
                        actorResolver, generationService, permissions,
                        new SecureRandomCanonicalIdGenerator(),
                        InterfaceApiTestSupport.requestProvider()))
                .setControllerAdvice(new SafeProblemHandler())
                .addInterceptors(InterfaceApiTestSupport.authenticationInterceptor())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void previewRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connectionId\":\"con_01J6MZ4H9ST5N7YQ2C8W3V0KFY\",\"question\":\"how many users?\",\"mode\":\"PREVIEW\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void previewRequiresWorkspaceHeader() throws Exception {
        mockMvc.perform(post("/api/v1/queries")
                        .with(InterfaceApiTestSupport.authenticated())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connectionId\":\"con_01J6MZ4H9ST5N7YQ2C8W3V0KFY\",\"question\":\"how many users?\",\"mode\":\"PREVIEW\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORKSPACE_SELECTOR_INVALID"));
    }

    @Test
    void previewRequiresQueryPermissionForMember() throws Exception {
        memberships.setCurrent(WORKSPACE, WorkspaceRole.MEMBER);

        mockMvc.perform(post("/api/v1/queries")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header(WorkspaceHeaderResolver.HEADER_NAME, WORKSPACE.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connectionId\":\"con_01J6MZ4H9ST5N7YQ2C8W3V0KFY\",\"question\":\"how many users?\",\"mode\":\"PREVIEW\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUERY_ACCESS_DENIED"));
    }

    @Test
    void previewReturnsReadyResponseForAdministrator() throws Exception {
        memberships.setCurrent(WORKSPACE, WorkspaceRole.ADMINISTRATOR);

        mockMvc.perform(post("/api/v1/queries")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header(WorkspaceHeaderResolver.HEADER_NAME, WORKSPACE.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connectionId\":\"con_01J6MZ4H9ST5N7YQ2C8W3V0KFY\",\"question\":\"how many users?\",\"mode\":\"PREVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.connectionId").value(CONNECTION.value()))
                .andExpect(jsonPath("$.sql").value("SELECT COUNT(*) FROM users"))
                .andExpect(jsonPath("$.explanation").value("Counts all users in the system"))
                .andExpect(jsonPath("$.assumptions[0]").value("Table users exists"))
                .andExpect(jsonPath("$.referencedObjects[0]").value("public.users"))
                .andExpect(jsonPath("$.policy.version").value("querysentry-1"))
                .andExpect(jsonPath("$.policy.maxRows").value(100))
                .andExpect(jsonPath("$.policy.statementTimeoutMs").value(15000));
    }

    @Test
    void previewReturnsReadyResponseForMemberWithQueryPermission() throws Exception {
        memberships.setCurrent(WORKSPACE, WorkspaceRole.MEMBER);
        permissions.grant(CONNECTION, Set.of(GrantablePermission.QUERY));

        mockMvc.perform(post("/api/v1/queries")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header(WorkspaceHeaderResolver.HEADER_NAME, WORKSPACE.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connectionId\":\"con_01J6MZ4H9ST5N7YQ2C8W3V0KFY\",\"question\":\"how many users?\",\"mode\":\"PREVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void previewReturnsNeedsClarification() throws Exception {
        memberships.setCurrent(WORKSPACE, WorkspaceRole.ADMINISTRATOR);
        generationService.setNeedsClarification(true);

        mockMvc.perform(post("/api/v1/queries")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header(WorkspaceHeaderResolver.HEADER_NAME, WORKSPACE.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connectionId\":\"con_01J6MZ4H9ST5N7YQ2C8W3V0KFY\",\"question\":\"what is the revenue?\",\"mode\":\"PREVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"));
    }

    @Test
    void executeEndpointReturnsNotImplemented() throws Exception {
        memberships.setCurrent(WORKSPACE, WorkspaceRole.ADMINISTRATOR);

        mockMvc.perform(post("/api/v1/queries/qry_" + "B".repeat(26) + "/execute")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header(WorkspaceHeaderResolver.HEADER_NAME, WORKSPACE.value()))
                .andExpect(status().is(501))
                .andExpect(jsonPath("$.code").value("QUERY_EXECUTION_NOT_IMPLEMENTED"));
    }

    static final class FakeMembershipRepository implements CurrentMembershipRepository {
        private final Map<String, CurrentMembership> byWorkspaceAndActor = new HashMap<>();

        void setCurrent(WorkspaceId workspaceId, WorkspaceRole role) {
            byWorkspaceAndActor.put(
                    workspaceId.value(),
                    new CurrentMembership(
                            ACTOR, workspaceId, MEMBERSHIP, role, MembershipStatus.ACTIVE));
        }

        @Override
        public Optional<CurrentMembership> findCurrentActiveMembership(
                String issuer, String subject, WorkspaceId workspaceId) {
            return Optional.ofNullable(byWorkspaceAndActor.get(workspaceId.value()));
        }

        @Override
        public List<MembershipDirectoryEntry> findActiveMembershipsForActor(String issuer, String subject) {
            return List.copyOf(byWorkspaceAndActor.values().stream()
                    .map(m -> new MembershipDirectoryEntry(m.workspaceId(), m.membershipId(), "subject", m.role()))
                    .toList());
        }

        @Override
        public List<MembershipDirectoryEntry> findActiveMemberships(
                WorkspaceId workspaceId, Optional<MembershipId> afterMembershipId, int limit) {
            return List.of();
        }

        @Override
        public Optional<MembershipDirectoryEntry> findActiveMembership(WorkspaceId workspaceId, MembershipId membershipId) {
            return Optional.empty();
        }
    }

    static final class FakeConnectionPermissionRepository implements ConnectionPermissionRepository {
        private final Map<String, Set<GrantablePermission>> grants = new HashMap<>();

        void grant(ConnectionId connectionId, Set<GrantablePermission> permissions) {
            grants.put(connectionId.value(), permissions);
        }

        @Override
        public Optional<ConnectionPermissionGrant> find(
                WorkspaceId workspaceId, ConnectionId connectionId, MembershipId membershipId) {
            Set<GrantablePermission> perms = grants.get(connectionId.value());
            if (perms == null) return Optional.empty();
            return Optional.of(new ConnectionPermissionGrant(
                    workspaceId, connectionId, membershipId, perms, 1L));
        }

        @Override
        public boolean replace(ConnectionPermissionGrant grant, long expectedCollectionVersion) {
            return false;
        }

        @Override
        public boolean revoke(
                WorkspaceId workspaceId, ConnectionId connectionId,
                MembershipId membershipId, long expectedCollectionVersion) {
            return false;
        }

        @Override
        public List<ConnectionPermissionGrant> findPage(
                WorkspaceId workspaceId, ConnectionId connectionId,
                Optional<MembershipId> afterMembershipId, int limit) {
            return List.of();
        }

        @Override
        public long currentCollectionVersion(WorkspaceId workspaceId, ConnectionId connectionId) {
            return 1L;
        }
    }

    static final class StubGenerationService extends GenerationService {
        private boolean needsClarification = false;

        StubGenerationService() {
            super(
                    new NoopSqlGenerationModel(),
                    new SchemaSelectorService(),
                    new SchemaQueryService(new NoopCatalogRepository()),
                    new QuerySentry(new JsqlParserSqlParser(), "querysentry-1",
                            new SecureRandomCanonicalIdGenerator()),
                    new FakeConnectionPermissionRepository(),
                    "querysentry-1"
            );
        }

        void setNeedsClarification(boolean b) {
            this.needsClarification = b;
        }

        @Override
        public GenerationResult ask(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId, String question) {
            if (needsClarification) {
                var proposal = new SqlProposal(
                        "N/A",
                        "N/A",
                        List.of(),
                        List.of(new CanonicalQualifiedName("public", "revenue")),
                        AmbiguityStatus.NEEDS_CLARIFICATION,
                        "Should revenue use gross_total or net_total?",
                        0,
                        List.of()
                );
                var response = new GenerationResponse(
                        AmbiguityStatus.NEEDS_CLARIFICATION,
                        proposal,
                        "v1",
                        "test-model",
                        100L
                );
                return new GenerationResult(response, null);
            }

            var proposal = new SqlProposal(
                    "SELECT COUNT(*) FROM users",
                    "Counts all users in the system",
                    List.of("Table users exists"),
                    List.of(new CanonicalQualifiedName("public", "users")),
                    AmbiguityStatus.READY,
                    null,
                    95,
                    List.of()
            );
            var response = new GenerationResponse(
                    AmbiguityStatus.READY,
                    proposal,
                    "v1",
                    "test-model",
                    500L
            );

            var approved = new ApprovedQuery(
                    new QueryRequestId("qry_" + "A".repeat(26)),
                    DatabaseKind.POSTGRESQL,
                    "SELECT COUNT(*) FROM users",
                    "abcdef1234567890",
                    List.of("public.users"),
                    List.of(),
                    Map.of("maxRows", 100, "statementTimeoutMs", 15000,
                           "maxTables", 10, "maxColumns", 20, "maxJoins", 5, "maxCteLevels", 5),
                    "querysentry-1",
                    Instant.now(),
                    Instant.now().plusSeconds(30)
            );
            return new GenerationResult(response, approved);
        }
    }

    static final class NoopSqlGenerationModel implements SqlGenerationModel {
        @Override
        public GenerationResponse generate(com.knowimran.rivoquery.generation.GenerationRequest request) {
            return new GenerationResponse(
                    AmbiguityStatus.READY,
                    new SqlProposal("SELECT 1", "noop", List.of(), List.of(),
                            AmbiguityStatus.READY, null, 100, List.of()),
                    "v1", "noop", 1L
            );
        }
    }

    static final class NoopCatalogRepository implements CatalogRepository {
        @Override
        public void insert(com.knowimran.rivoquery.catalog.SchemaSnapshot snapshot) {}

        @Override
        public void installCurrentSnapshot(WorkspaceId workspaceId, ConnectionId connectionId,
                                           com.knowimran.rivoquery.catalog.SchemaSnapshot snapshot) {}

        @Override
        public Optional<com.knowimran.rivoquery.catalog.SchemaSnapshot> findCurrentSnapshot(
                WorkspaceId workspaceId, ConnectionId connectionId) { return Optional.empty(); }

        @Override
        public Optional<com.knowimran.rivoquery.catalog.SchemaSnapshot> findSnapshot(
                WorkspaceId workspaceId, ConnectionId connectionId,
                com.knowimran.rivoquery.catalog.CatalogObjectId snapshotId) { return Optional.empty(); }

        @Override
        public com.knowimran.rivoquery.catalog.SchemaStatus findStatus(
                WorkspaceId workspaceId, ConnectionId connectionId) { return null; }

        @Override
        public com.knowimran.rivoquery.catalog.SchemaObjectPage findObjects(
                WorkspaceId workspaceId, ConnectionId connectionId, String cursor, int limit) {
            return new com.knowimran.rivoquery.catalog.SchemaObjectPage(List.of(), null);
        }
    }
}
