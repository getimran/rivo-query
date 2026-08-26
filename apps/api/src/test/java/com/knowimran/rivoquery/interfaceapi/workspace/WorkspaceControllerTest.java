package com.knowimran.rivoquery.interfaceapi.workspace;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.CurrentMembership;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.MembershipStatus;
import com.knowimran.rivoquery.identity.WorkspaceActorResolver;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import com.knowimran.rivoquery.identity.application.CurrentMembershipRepository;
import com.knowimran.rivoquery.identity.application.MembershipDirectoryEntry;
import com.knowimran.rivoquery.identity.application.WorkspaceDirectory;
import com.knowimran.rivoquery.interfaceapi.InterfaceApiTestSupport;
import com.knowimran.rivoquery.interfaceapi.RequestIdFilter;
import com.knowimran.rivoquery.interfaceapi.SafeProblemHandler;
import com.knowimran.rivoquery.interfaceapi.support.ActorResolver;
import com.knowimran.rivoquery.interfaceapi.support.WorkspaceHeaderResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkspaceControllerTest {

    private static final WorkspaceId WORKSPACE_A = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final WorkspaceId WORKSPACE_B = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY");
    private static final ActorId ACTOR = new ActorId("act_01J6MZ4H9ST5N7YQ2C8W3V0KFX");

    private FakeMembershipRepository memberships;
    private FakeWorkspaceDirectory directory;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        memberships = new FakeMembershipRepository();
        directory = new FakeWorkspaceDirectory();
        var actorResolver = new ActorResolver(new WorkspaceActorResolver(memberships));
        mockMvc = MockMvcBuilders.standaloneSetup(new WorkspaceController(
                        memberships, directory, actorResolver, InterfaceApiTestSupport.requestProvider()))
                .setControllerAdvice(new SafeProblemHandler())
                .addInterceptors(InterfaceApiTestSupport.authenticationInterceptor())
                .addFilters(new RequestIdFilter())
                .build();
    }

    // ------------------------------------------------------------------
    // discoverWorkspaces (unscoped)
    // ------------------------------------------------------------------

    @Test
    void discoverWorkspacesRequiresAuthenticationBeforeAnythingElse() throws Exception {
        mockMvc.perform(get("/api/v1/workspaces").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void discoverWorkspacesRejectsAWorkspaceSelector() throws Exception {
        mockMvc.perform(get("/api/v1/workspaces")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header(WorkspaceHeaderResolver.HEADER_NAME, WORKSPACE_A.value())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORKSPACE_SELECTOR_NOT_ALLOWED"));
    }

    @Test
    void discoverWorkspacesReturnsOnlyTheCallersOwnMemberships() throws Exception {
        memberships.add(WORKSPACE_A, "mem_01J6MZ4H9ST5N7YQ2C8W3V0KFX", WorkspaceRole.ADMINISTRATOR);
        directory.put(WORKSPACE_A, "Alpha Workspace");

        mockMvc.perform(get("/api/v1/workspaces").with(InterfaceApiTestSupport.authenticated()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectionRequired").value(true))
                .andExpect(jsonPath("$.items[0].workspaceId").value(WORKSPACE_A.value()))
                .andExpect(jsonPath("$.items[0].displayName").value("Alpha Workspace"))
                .andExpect(jsonPath("$.items[0].role").value("ADMINISTRATOR"));
    }

    @Test
    void discoverWorkspacesDegradesGracefullyWhenTheWorkspaceDisplayNameIsUnavailable() throws Exception {
        memberships.add(WORKSPACE_A, "mem_01J6MZ4H9ST5N7YQ2C8W3V0KFX", WorkspaceRole.MEMBER);
        // Deliberately no directory.put(...): the workspace row is missing.

        mockMvc.perform(get("/api/v1/workspaces").with(InterfaceApiTestSupport.authenticated()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].displayName").value("(workspace name unavailable)"));
    }

    @Test
    void discoverWorkspacesPaginatesAndSetsNextCursor() throws Exception {
        memberships.add(WORKSPACE_A, "mem_01J6MZ4H9ST5N7YQ2C8W3V0KFX", WorkspaceRole.MEMBER);
        memberships.add(WORKSPACE_B, "mem_01J6MZ4H9ST5N7YQ2C8W3V0KFY", WorkspaceRole.MEMBER);
        directory.put(WORKSPACE_A, "Alpha");
        directory.put(WORKSPACE_B, "Beta");

        mockMvc.perform(get("/api/v1/workspaces")
                        .with(InterfaceApiTestSupport.authenticated())
                        .param("limit", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].workspaceId").value(WORKSPACE_A.value()))
                .andExpect(jsonPath("$.nextCursor").value(WORKSPACE_A.value()));
    }

    @Test
    void discoverWorkspacesRejectsAMalformedCursor() throws Exception {
        mockMvc.perform(get("/api/v1/workspaces")
                        .with(InterfaceApiTestSupport.authenticated())
                        .param("cursor", "not-a-workspace-id-but-16-chars")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ------------------------------------------------------------------
    // listWorkspaceMemberships (scoped, administrator-only)
    // ------------------------------------------------------------------

    @Test
    void listMembershipsRequiresAuthenticationBeforeTheWorkspaceHeaderIsInspected() throws Exception {
        mockMvc.perform(get("/api/v1/workspace-memberships").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void listMembershipsRejectsAnExactDuplicateWorkspaceHeader() throws Exception {
        mockMvc.perform(get("/api/v1/workspace-memberships")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header(WorkspaceHeaderResolver.HEADER_NAME, WORKSPACE_A.value())
                        .header(WorkspaceHeaderResolver.HEADER_NAME, WORKSPACE_A.value())
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORKSPACE_SELECTOR_INVALID"));
    }

    @Test
    void listMembershipsRejectsACallerWithNoMembershipInTheSelectedWorkspace() throws Exception {
        mockMvc.perform(get("/api/v1/workspace-memberships")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header(WorkspaceHeaderResolver.HEADER_NAME, WORKSPACE_A.value())
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    void listMembershipsRejectsANonAdministratorMember() throws Exception {
        memberships.setCurrent(WORKSPACE_A, WorkspaceRole.MEMBER);

        mockMvc.perform(get("/api/v1/workspace-memberships")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header(WorkspaceHeaderResolver.HEADER_NAME, WORKSPACE_A.value())
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMINISTRATOR_REQUIRED"));
    }

    @Test
    void listMembershipsReturnsTheSelectedWorkspacesMembershipsOnlyForAnAdministrator() throws Exception {
        memberships.setCurrent(WORKSPACE_A, WorkspaceRole.ADMINISTRATOR);
        memberships.add(WORKSPACE_A, "mem_01J6MZ4H9ST5N7YQ2C8W3V0KFX", WorkspaceRole.MEMBER);

        mockMvc.perform(get("/api/v1/workspace-memberships")
                        .with(InterfaceApiTestSupport.authenticated())
                        .header(WorkspaceHeaderResolver.HEADER_NAME, WORKSPACE_A.value())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].membershipId").value("mem_01J6MZ4H9ST5N7YQ2C8W3V0KFX"))
                .andExpect(jsonPath("$.items[0].role").value("MEMBER"));
    }

    private static final class FakeMembershipRepository implements CurrentMembershipRepository {
        private final Map<String, CurrentMembership> byWorkspaceAndActor = new HashMap<>();
        private final List<MembershipDirectoryEntry> directory = new ArrayList<>();

        void setCurrent(WorkspaceId workspaceId, WorkspaceRole role) {
            byWorkspaceAndActor.put(
                    workspaceId.value(),
                    new CurrentMembership(
                            ACTOR, workspaceId, new MembershipId("mem_01J6MZ4H9ST5N7YQ2C8W3V0KFZ"),
                            role, MembershipStatus.ACTIVE));
        }

        void add(WorkspaceId workspaceId, String membershipId, WorkspaceRole role) {
            directory.add(new MembershipDirectoryEntry(workspaceId, new MembershipId(membershipId), "subject", role));
        }

        @Override
        public Optional<CurrentMembership> findCurrentActiveMembership(
                String issuer, String subject, WorkspaceId workspaceId) {
            return Optional.ofNullable(byWorkspaceAndActor.get(workspaceId.value()));
        }

        @Override
        public List<MembershipDirectoryEntry> findActiveMembershipsForActor(String issuer, String subject) {
            return List.copyOf(directory);
        }

        @Override
        public List<MembershipDirectoryEntry> findActiveMemberships(
                WorkspaceId workspaceId, Optional<MembershipId> afterMembershipId, int limit) {
            String after = afterMembershipId.map(MembershipId::value).orElse("");
            return directory.stream()
                    .filter(entry -> entry.workspaceId().equals(workspaceId))
                    .filter(entry -> entry.membershipId().value().compareTo(after) > 0)
                    .sorted(java.util.Comparator.comparing(entry -> entry.membershipId().value()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<MembershipDirectoryEntry> findActiveMembership(WorkspaceId workspaceId, MembershipId membershipId) {
            return directory.stream()
                    .filter(entry -> entry.workspaceId().equals(workspaceId) && entry.membershipId().equals(membershipId))
                    .findFirst();
        }
    }

    private static final class FakeWorkspaceDirectory implements WorkspaceDirectory {
        private final Map<String, String> byWorkspace = new HashMap<>();

        void put(WorkspaceId workspaceId, String displayName) {
            byWorkspace.put(workspaceId.value(), displayName);
        }

        @Override
        public Optional<String> displayNameOf(WorkspaceId workspaceId) {
            return Optional.ofNullable(byWorkspace.get(workspaceId.value()));
        }
    }
}
