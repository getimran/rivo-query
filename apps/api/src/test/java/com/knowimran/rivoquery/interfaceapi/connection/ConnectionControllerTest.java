package com.knowimran.rivoquery.interfaceapi.connection;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionPermissionGrant;
import com.knowimran.rivoquery.connection.application.ConnectionProfileRepository;
import com.knowimran.rivoquery.connection.application.IdempotencyKeyRepository;
import com.knowimran.rivoquery.connection.application.IdempotencyRecord;
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
import com.knowimran.rivoquery.interfaceapi.generated.model.ConnectorOptions;
import com.knowimran.rivoquery.interfaceapi.generated.model.CreateConnectionRequest;
import com.knowimran.rivoquery.interfaceapi.generated.model.DatabaseKind;
import com.knowimran.rivoquery.interfaceapi.generated.model.EndpointInput;
import com.knowimran.rivoquery.interfaceapi.generated.model.ObjectAccessPolicy;
import com.knowimran.rivoquery.interfaceapi.generated.model.RotateSecretRequest;
import com.knowimran.rivoquery.interfaceapi.generated.model.SecretInput;
import com.knowimran.rivoquery.interfaceapi.generated.model.TlsInput;
import com.knowimran.rivoquery.interfaceapi.generated.model.TlsMode;
import com.knowimran.rivoquery.interfaceapi.generated.model.UpdateConnectionRequest;
import com.knowimran.rivoquery.interfaceapi.support.ActorResolver;
import com.knowimran.rivoquery.interfaceapi.support.IdempotencyGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConnectionControllerTest {

    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final WorkspaceId OTHER_WORKSPACE = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY");
    private static final ActorId ADMIN_ACTOR = new ActorId("act_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final MembershipId MEMBER_MEMBERSHIP = new MembershipId("mem_01J6MZ4H9ST5N7YQ2C8W3V0KFY");
    private static final String SECRET_PASSWORD = "correct-horse-battery-staple";

    private final ObjectMapper json = new ObjectMapper();

    private ConnectionControllerTestFixtures.FakeConnectionRepository connections;
    private ConnectionControllerTestFixtures.FakeDurableOperationRepository operations;
    private ConnectionControllerTestFixtures.FakeConnectionPermissionRepository permissions;
    private FakeConnectionProfileRepository profiles;
    private FakeIdempotencyKeyRepository idempotencyKeys;
    private FakeMembershipRepository memberships;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        connections = new ConnectionControllerTestFixtures.FakeConnectionRepository();
        operations = new ConnectionControllerTestFixtures.FakeDurableOperationRepository();
        permissions = new ConnectionControllerTestFixtures.FakeConnectionPermissionRepository();
        var audit = new ConnectionControllerTestFixtures.FakeConnectionAuditPort();
        var secretStore = new ConnectionControllerTestFixtures.FakeSecretStore();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var destinationPolicy = new ConnectionControllerTestFixtures.FakeDestinationPolicy(clock);
        var connector = new ConnectionControllerTestFixtures.FakeDatabaseConnector();
        var pool = new ConnectionControllerTestFixtures.FakeTargetPoolManager();

        var lifecycle = ConnectionControllerTestFixtures.lifecycleService(
                connections, operations, audit, secretStore, destinationPolicy, connector, pool, clock);
        var queries = ConnectionControllerTestFixtures.queryService(connections, permissions);
        profiles = new FakeConnectionProfileRepository();
        idempotencyKeys = new FakeIdempotencyKeyRepository();
        memberships = new FakeMembershipRepository();
        memberships.setCurrent(WORKSPACE, WorkspaceRole.ADMINISTRATOR);

        var actorResolver = new ActorResolver(new WorkspaceActorResolver(memberships));
        var controller = new ConnectionController(
                actorResolver, lifecycle, queries, profiles, operations, new IdempotencyGuard(idempotencyKeys),
                InterfaceApiTestSupport.requestProvider(), clock);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new SafeProblemHandler())
                .addInterceptors(InterfaceApiTestSupport.authenticationInterceptor())
                .addFilters(new RequestIdFilter())
                .build();
    }

    // ------------------------------------------------------------------
    // auth-before-load
    // ------------------------------------------------------------------

    @Test
    void getConnectionRequiresAuthenticationBeforeAnyLookup() throws Exception {
        mockMvc.perform(get("/api/v1/connections/con_01J6MZ4H9ST5N7YQ2C8W3V0KG9")
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void getConnectionRejectsAnExactDuplicateWorkspaceHeader() throws Exception {
        var connectionId = connections.seedActive(WORKSPACE, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KG9")).id();

        mockMvc.perform(get("/api/v1/connections/" + connectionId.value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORKSPACE_SELECTOR_INVALID"));
    }

    // ------------------------------------------------------------------
    // cross-tenant negative matrix
    // ------------------------------------------------------------------

    @Test
    void getConnectionInAForeignWorkspaceIsIndistinguishableFromUnknown() throws Exception {
        var connectionId = connections.seedActive(OTHER_WORKSPACE, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KG9")).id();

        mockMvc.perform(get("/api/v1/connections/" + connectionId.value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONNECTION_NOT_FOUND"));
    }

    @Test
    void callerWithNoMembershipAtAllInTheSelectedWorkspaceIsDeniedBeforeAnyConnectionLookup() throws Exception {
        mockMvc.perform(get("/api/v1/connections/con_01J6MZ4H9ST5N7YQ2C8W3V0KG9")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", OTHER_WORKSPACE.value())
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    /**
     * A member with <em>no</em> grant on the connection must get the same
     * indistinguishable {@code 404 CONNECTION_NOT_FOUND} an unknown or
     * foreign-tenant id produces - not a {@code 403}, which would leak the
     * connection's existence to an actor without view access.
     */
    @Test
    void nonAdministratorMemberWithoutAGrantCannotGetAConnectionDetail() throws Exception {
        memberships.setCurrent(WORKSPACE, WorkspaceRole.MEMBER);
        var connectionId = connections.seedActive(WORKSPACE, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KG9")).id();

        mockMvc.perform(get("/api/v1/connections/" + connectionId.value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONNECTION_NOT_FOUND"));
    }

    @Test
    void memberWithAGrantCanGetAConnectionDetail() throws Exception {
        memberships.setCurrent(WORKSPACE, WorkspaceRole.MEMBER);
        var connectionId = connections.seedActive(WORKSPACE, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KG9")).id();
        permissions.put(new ConnectionPermissionGrant(
                WORKSPACE, connectionId, MEMBER_MEMBERSHIP, Set.of(GrantablePermission.VIEW_SCHEMA), 1L));

        mockMvc.perform(get("/api/v1/connections/" + connectionId.value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionId").value(connectionId.value()))
                .andExpect(jsonPath("$.secretConfigured").value(true));
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Test
    void createConnectionSucceedsAndNeverLeaksTheSubmittedSecret() throws Exception {
        String response = mockMvc.perform(post("/api/v1/connections")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody("primary"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().exists("ETag"))
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.name").value("primary"))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.secretConfigured").value(true))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(response).doesNotContain(SECRET_PASSWORD);
    }

    @Test
    void createConnectionRejectsAnUnpairedClientCertificate() throws Exception {
        CreateConnectionRequest body = createRequest("primary");
        body.getTls().setClientCertificatePem("cert-without-a-key");

        mockMvc.perform(post("/api/v1/connections")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body))
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void exactIdempotentReplayOfCreateReturnsTheOriginalConnectionWithoutCreatingASecondOne() throws Exception {
        String body = createRequestBody("primary");

        String first = mockMvc.perform(post("/api/v1/connections")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstConnectionId = json.readTree(first).get("connectionId").asText();

        String second = mockMvc.perform(post("/api/v1/connections")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String secondConnectionId = json.readTree(second).get("connectionId").asText();

        org.assertj.core.api.Assertions.assertThat(secondConnectionId).isEqualTo(firstConnectionId);
        org.assertj.core.api.Assertions.assertThat(connections.findPage(WORKSPACE, Optional.empty(), 100)).hasSize(1);
    }

    /**
     * B2 regression: two concurrent {@code POST /connections} requests
     * carrying the same fresh {@code Idempotency-Key} and body must never
     * both run {@code ConnectionLifecycleService#create} to completion -
     * before the fix, both requests independently observed "no existing
     * reservation" and both created a fully separate {@code ACTIVE}
     * connection with its own staged secret, directly contradicting the
     * OpenAPI contract's idempotency guarantee. Uses a real {@link
     * CountDownLatch} barrier so both requests genuinely race on the same
     * underlying thread-safe fakes (see {@code
     * ConnectionControllerTestFixtures}' concurrency comments) rather than
     * merely replaying sequentially like the test above.
     */
    @Test
    void concurrentCreateRequestsWithTheSameIdempotencyKeyNeverBothCreateAConnection() throws Exception {
        String body = createRequestBody("primary");
        int racers = 6;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(racers);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(racers);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Integer> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<Exception> unexpectedFailures = new java.util.concurrent.CopyOnWriteArrayList<>();

        try {
            for (int i = 0; i < racers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        int status = mockMvc.perform(post("/api/v1/connections")
                                        .with(InterfaceApiTestSupport.authenticated())
                                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                                        .header("Idempotency-Key", "idem-key-race-1234567890ab")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body)
                                        .accept(MediaType.APPLICATION_JSON))
                                .andReturn().getResponse().getStatus();
                        statuses.add(status);
                    } catch (Exception unexpected) {
                        unexpectedFailures.add(unexpected);
                    }
                });
            }
            org.assertj.core.api.Assertions.assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            go.countDown();
        } finally {
            pool.shutdown();
            org.assertj.core.api.Assertions.assertThat(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }

        org.assertj.core.api.Assertions.assertThat(unexpectedFailures).isEmpty();
        // Every racer gets a definite response: 201 (the winner, or a clean
        // replay once the winner has completed) or 409 IDEMPOTENCY_KEY_IN_FLIGHT
        // (a loser that raced while the winner was still mid-flight) - never
        // a 500, and never silently duplicated.
        org.assertj.core.api.Assertions.assertThat(statuses).allSatisfy(status ->
                org.assertj.core.api.Assertions.assertThat(status).isIn(201, 409));
        org.assertj.core.api.Assertions.assertThat(statuses).contains(201);

        // The only real proof that matters: exactly one connection (and by
        // extension exactly one staged secret - create stages a secret
        // before promoting the connection to ACTIVE) exists afterward.
        org.assertj.core.api.Assertions.assertThat(connections.findPage(WORKSPACE, Optional.empty(), 100)).hasSize(1);
    }

    @Test
    void reusingTheIdempotencyKeyWithADifferentCreateBodyIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/connections")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody("primary"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/connections")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody("a-different-name"))
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    // ------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------

    @Test
    void updateRejectsAnEmptyPatchBody() throws Exception {
        var connectionId = connections.seedActive(WORKSPACE, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KG9")).id();

        mockMvc.perform(patch("/api/v1/connections/" + connectionId.value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void updateWithAStaleIfMatchIsAPreconditionFailure() throws Exception {
        var connection = connections.seedActive(WORKSPACE, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KG9"));

        mockMvc.perform(patch("/api/v1/connections/" + connection.id().value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"999\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateConnectionRequest().name("renamed")))
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().is(412))
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
    }

    @Test
    void updateAdvancesTheVersionAndPersistsTheNewName() throws Exception {
        var connection = connections.seedActive(WORKSPACE, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KG9"));

        mockMvc.perform(patch("/api/v1/connections/" + connection.id().value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"" + connection.version().value() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateConnectionRequest().name("renamed")))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed"));
    }

    // ------------------------------------------------------------------
    // disable / delete idempotency
    // ------------------------------------------------------------------

    @Test
    void exactIdempotentReplayOfDisableReturnsTheSameResultWithoutReapplying() throws Exception {
        var connection = connections.seedActive(WORKSPACE, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KG9"));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/connections/" + connection.id().value() + "/disable")
                            .with(InterfaceApiTestSupport.authenticated())
                            .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                            .header("Idempotency-Key", "idem-key-1234567890abcdef")
                            .header("If-Match", "\"" + connection.version().value() + "\"")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("DISABLED"));
        }
    }

    @Test
    void deleteTombstonesTheConnectionAndReturnsAnAcceptedOperation() throws Exception {
        var connection = connections.seedActive(WORKSPACE, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KG9"));

        mockMvc.perform(delete("/api/v1/connections/" + connection.id().value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"" + connection.version().value() + "\"")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.action").value("CONNECTION_DELETE"));
    }

    // ------------------------------------------------------------------
    // enable / test / rotate-secret: honest "not yet supported"
    // ------------------------------------------------------------------

    @Test
    void enableIsAStableNotYetSupportedFailure() throws Exception {
        var connection = connections.seedActive(WORKSPACE, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KG9"));

        mockMvc.perform(post("/api/v1/connections/" + connection.id().value() + "/enable")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"" + connection.version().value() + "\"")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CONNECTION_ENABLE_NOT_YET_SUPPORTED"));
    }

    @Test
    void updateRejectsANonNullEndpointRatherThanPersistingItUnvalidated() throws Exception {
        var connection = connections.seedActive(WORKSPACE, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KG9"));

        mockMvc.perform(patch("/api/v1/connections/" + connection.id().value())
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"" + connection.version().value() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateConnectionRequest()
                                .endpoint(new EndpointInput("169.254.169.254", 5432, "app"))))
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONNECTION_ENDPOINT_UPDATE_NOT_YET_SUPPORTED"));

        // No profile write occurred for the rejected field.
        org.assertj.core.api.Assertions.assertThat(profiles.find(WORKSPACE, connection.id())).isEmpty();
    }

    @Test
    void testConnectionIsAStableNotYetSupportedFailure() throws Exception {
        var connection = connections.seedActive(WORKSPACE, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KG9"));

        mockMvc.perform(post("/api/v1/connections/" + connection.id().value() + "/test")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"" + connection.version().value() + "\"")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CONNECTION_TEST_NOT_YET_SUPPORTED"));
    }

    @Test
    void rotateSecretRejectsAnUnpairedClientKeyAndNeverStagesIt() throws Exception {
        var connection = connections.seedActive(WORKSPACE, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KG9"));
        RotateSecretRequest body = new RotateSecretRequest(new SecretInput("service-account", SECRET_PASSWORD));
        body.setClientPrivateKeyPem("key-without-a-certificate");

        mockMvc.perform(post("/api/v1/connections/" + connection.id().value() + "/rotate-secret")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header("X-RivoQuery-Workspace-Id", WORKSPACE.value())
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"" + connection.version().value() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body))
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private String createRequestBody(String name) throws Exception {
        return json.writeValueAsString(createRequest(name));
    }

    private static CreateConnectionRequest createRequest(String name) {
        return new CreateConnectionRequest(
                name,
                DatabaseKind.POSTGRESQL,
                new EndpointInput("db.example.internal", 5432, "app"),
                new SecretInput("service-account", SECRET_PASSWORD),
                new TlsInput(TlsMode.VERIFY_FULL),
                new ConnectorOptions().tcpKeepAlive(true),
                new ObjectAccessPolicy(Set.of("public"), Set.of()),
                new com.knowimran.rivoquery.interfaceapi.generated.model.ConnectionLimits(30000, 1000));
    }

    private static final class FakeConnectionProfileRepository implements ConnectionProfileRepository {
        // ConcurrentHashMap: see ConnectionControllerTestFixtures.FakeConnectionRepository's identical comment.
        private final Map<String, com.knowimran.rivoquery.connection.application.ConnectionProfile> byKey =
                new java.util.concurrent.ConcurrentHashMap<>();

        private static String key(WorkspaceId workspaceId, ConnectionId connectionId) {
            return workspaceId.value() + "/" + connectionId.value();
        }

        @Override
        public Optional<com.knowimran.rivoquery.connection.application.ConnectionProfile> find(
                WorkspaceId workspaceId, ConnectionId connectionId) {
            return Optional.ofNullable(byKey.get(key(workspaceId, connectionId)));
        }

        @Override
        public void insert(com.knowimran.rivoquery.connection.application.ConnectionProfile profile) {
            byKey.put(key(profile.workspaceId(), profile.connectionId()), profile);
        }

        @Override
        public void save(com.knowimran.rivoquery.connection.application.ConnectionProfile profile) {
            byKey.put(key(profile.workspaceId(), profile.connectionId()), profile);
        }

        @Override
        public void delete(WorkspaceId workspaceId, ConnectionId connectionId) {
            byKey.remove(key(workspaceId, connectionId));
        }
    }

    /**
     * A real, thread-safe fake (backed by {@link java.util.concurrent.ConcurrentHashMap}'s
     * atomic {@code putIfAbsent}) rather than a {@code HashMap} - {@link
     * #tryReserve} must genuinely behave like the production {@code
     * JdbcIdempotencyKeyRepository}'s unique-constraint-backed atomicity for
     * {@link ConnectionControllerTest}'s concurrent-create race test to be
     * meaningful.
     */
    private static final class FakeIdempotencyKeyRepository implements IdempotencyKeyRepository {
        private final java.util.concurrent.ConcurrentMap<String, IdempotencyRecord> byKey =
                new java.util.concurrent.ConcurrentHashMap<>();

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

    private static final class FakeMembershipRepository implements CurrentMembershipRepository {
        private final Map<String, WorkspaceRole> currentByWorkspace = new HashMap<>();

        void setCurrent(WorkspaceId workspaceId, WorkspaceRole role) {
            currentByWorkspace.put(workspaceId.value(), role);
        }

        @Override
        public Optional<CurrentMembership> findCurrentActiveMembership(String issuer, String subject, WorkspaceId workspaceId) {
            WorkspaceRole role = currentByWorkspace.get(workspaceId.value());
            if (role == null) {
                return Optional.empty();
            }
            return Optional.of(new CurrentMembership(ADMIN_ACTOR, workspaceId, MEMBER_MEMBERSHIP, role, MembershipStatus.ACTIVE));
        }

        @Override
        public java.util.List<MembershipDirectoryEntry> findActiveMembershipsForActor(String issuer, String subject) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<MembershipDirectoryEntry> findActiveMemberships(
                WorkspaceId workspaceId, Optional<MembershipId> afterMembershipId, int limit) {
            return java.util.List.of();
        }

        @Override
        public Optional<MembershipDirectoryEntry> findActiveMembership(WorkspaceId workspaceId, MembershipId membershipId) {
            return Optional.empty();
        }
    }
}
