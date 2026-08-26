package com.knowimran.rivoquery.interfaceapi.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.identity.CurrentMembership;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.MembershipStatus;
import com.knowimran.rivoquery.identity.WorkspaceActorResolver;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import com.knowimran.rivoquery.identity.application.CurrentMembershipRepository;
import com.knowimran.rivoquery.identity.application.MembershipDirectoryEntry;
import com.knowimran.rivoquery.interfaceapi.auth.AuthenticatedPrincipal;
import com.knowimran.rivoquery.interfaceapi.auth.AuthenticationFailedException;
import com.knowimran.rivoquery.interfaceapi.auth.AuthenticationInterceptor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * PD2-003: authenticate (step 1) before resolving the workspace selector
 * (step 2) before resolving server-side membership (steps 3-4), strictly
 * before any resource lookup.
 */
class ActorResolverTest {

    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final WorkspaceId OTHER_WORKSPACE = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY");

    @Test
    void unauthenticatedRequestFailsBeforeWorkspaceHeaderIsEvenInspected() {
        var resolver = new ActorResolver(new WorkspaceActorResolver(new StubMembershipRepository(Optional.empty())));
        var request = new MockHttpServletRequest();
        // Deliberately no X-RivoQuery-Workspace-Id header either - if this
        // resolved authentication second, it would throw
        // WorkspaceSelectorInvalidException instead.

        assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void authenticatedButMissingWorkspaceHeaderIsRejected() {
        var resolver = new ActorResolver(new WorkspaceActorResolver(new StubMembershipRepository(Optional.empty())));
        var request = authenticated();

        assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOf(WorkspaceSelectorInvalidException.class);
    }

    @Test
    void noActiveMembershipInSelectedWorkspaceIsWorkspaceAccessDenied() {
        var resolver = new ActorResolver(new WorkspaceActorResolver(new StubMembershipRepository(Optional.empty())));
        var request = authenticated();
        request.addHeader(WorkspaceHeaderResolver.HEADER_NAME, WORKSPACE.value());

        assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOf(WorkspaceAccessDeniedException.class);
    }

    @Test
    void validMembershipResolvesToAnActorContext() {
        var membership = new CurrentMembership(
                new com.knowimran.rivoquery.identity.ActorId("act_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                WORKSPACE, new MembershipId("mem_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                WorkspaceRole.ADMINISTRATOR, MembershipStatus.ACTIVE);
        var resolver = new ActorResolver(new WorkspaceActorResolver(new StubMembershipRepository(Optional.of(membership))));
        var request = authenticated();
        request.addHeader(WorkspaceHeaderResolver.HEADER_NAME, WORKSPACE.value());

        var actor = resolver.resolve(request);

        assertThat(actor.workspaceId()).isEqualTo(WORKSPACE);
        assertThat(actor.role()).isEqualTo(WorkspaceRole.ADMINISTRATOR);
    }

    @Test
    void membershipInADifferentWorkspaceThanSelectedIsWorkspaceAccessDenied() {
        var membership = new CurrentMembership(
                new com.knowimran.rivoquery.identity.ActorId("act_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                OTHER_WORKSPACE, new MembershipId("mem_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                WorkspaceRole.ADMINISTRATOR, MembershipStatus.ACTIVE);
        var resolver = new ActorResolver(new WorkspaceActorResolver(new StubMembershipRepository(Optional.of(membership))));
        var request = authenticated();
        request.addHeader(WorkspaceHeaderResolver.HEADER_NAME, WORKSPACE.value());

        assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOf(WorkspaceAccessDeniedException.class);
    }

    private static MockHttpServletRequest authenticated() {
        var request = new MockHttpServletRequest();
        request.setAttribute(
                AuthenticationInterceptor.PRINCIPAL_ATTRIBUTE,
                new AuthenticatedPrincipal("issuer", "subject"));
        return request;
    }

    private static final class StubMembershipRepository implements CurrentMembershipRepository {
        private final Optional<CurrentMembership> membership;

        StubMembershipRepository(Optional<CurrentMembership> membership) {
            this.membership = membership;
        }

        @Override
        public Optional<CurrentMembership> findCurrentActiveMembership(
                String issuer, String subject, WorkspaceId workspaceId) {
            return membership.filter(m -> m.workspaceId().equals(workspaceId));
        }

        @Override
        public List<MembershipDirectoryEntry> findActiveMembershipsForActor(String issuer, String subject) {
            return List.of();
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
}
