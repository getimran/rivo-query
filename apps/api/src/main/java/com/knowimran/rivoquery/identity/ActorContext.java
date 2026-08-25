package com.knowimran.rivoquery.identity;

import com.knowimran.rivoquery.identity.application.CurrentMembershipRepository;
import java.util.Objects;

public final class ActorContext {
    private final ActorId actorId;
    private final WorkspaceId workspaceId;
    private final MembershipId membershipId;
    private final WorkspaceRole role;

    private ActorContext(
            ActorId actorId,
            WorkspaceId workspaceId,
            MembershipId membershipId,
            WorkspaceRole role) {
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.membershipId = Objects.requireNonNull(membershipId, "membershipId");
        this.role = Objects.requireNonNull(role, "role");
    }

    public ActorId actorId() { return actorId; }
    public WorkspaceId workspaceId() { return workspaceId; }
    public MembershipId membershipId() { return membershipId; }
    public WorkspaceRole role() { return role; }

    public void requireWorkspace(WorkspaceId requiredWorkspace) {
        if (!workspaceId.equals(requiredWorkspace)) {
            throw new AuthorizationDeniedException();
        }
    }

    public void requireAdministrator() {
        if (role != WorkspaceRole.ADMINISTRATOR) {
            throw new AuthorizationDeniedException();
        }
    }

    public static final class Authority {
        private final CurrentMembershipRepository memberships;

        public Authority(CurrentMembershipRepository memberships) {
            this.memberships = Objects.requireNonNull(memberships, "memberships");
        }

        public ActorContext resolve(String issuer, String subject, WorkspaceId selectedWorkspace) {
            requireExactIdentityPart(issuer, "issuer");
            requireExactIdentityPart(subject, "subject");
            Objects.requireNonNull(selectedWorkspace, "selectedWorkspace");
            var membership = memberships.findCurrentActiveMembership(issuer, subject, selectedWorkspace)
                    .orElseThrow(AuthorizationDeniedException::new);
            if (membership.status() != MembershipStatus.ACTIVE
                    || !membership.workspaceId().equals(selectedWorkspace)) {
                throw new AuthorizationDeniedException();
            }
            return new ActorContext(
                    membership.actorId(), membership.workspaceId(), membership.membershipId(), membership.role());
        }

        private static void requireExactIdentityPart(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank() || !value.equals(value.strip())) {
                throw new AuthorizationDeniedException();
            }
        }
    }
}
