package com.knowimran.rivoquery.identity;

import java.util.Objects;

public record CurrentMembership(
        ActorId actorId,
        WorkspaceId workspaceId,
        MembershipId membershipId,
        WorkspaceRole role,
        MembershipStatus status) {
    public CurrentMembership {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(membershipId, "membershipId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(status, "status");
    }

}
