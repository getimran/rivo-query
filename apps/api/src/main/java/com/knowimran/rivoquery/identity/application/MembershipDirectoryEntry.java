package com.knowimran.rivoquery.identity.application;

import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import java.util.Objects;

/**
 * A directory-listing row for workspace discovery / membership
 * administration. {@code displayName} is the actor's external OIDC {@code
 * subject} identifier: the {@code actors} table (P2-060 schema) has no
 * separate display-name column, and populating one requires an
 * actor-profile/identity-provider-claim decision explicitly deferred by
 * docs/product-decisions/phase-2.md ("How the first workspace and
 * administrator are provisioned ... must not be invented by engineering").
 * Using {@code subject} is an honest, non-fabricated placeholder - it really
 * is the actor's own identifier - rather than inventing a display name; see
 * docs/IMPLEMENTATION_TASKS.md P2-130 evidence.
 */
public record MembershipDirectoryEntry(
        WorkspaceId workspaceId, MembershipId membershipId, String displayName, WorkspaceRole role) {
    public MembershipDirectoryEntry {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(membershipId, "membershipId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(role, "role");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }
}
