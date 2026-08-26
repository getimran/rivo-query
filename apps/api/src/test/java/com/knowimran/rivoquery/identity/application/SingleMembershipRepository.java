package com.knowimran.rivoquery.identity.application;

import com.knowimran.rivoquery.identity.CurrentMembership;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.List;
import java.util.Optional;

/**
 * Shared test fixture: a {@link CurrentMembershipRepository} that always
 * resolves to one fixed membership, regardless of the requested issuer,
 * subject, or workspace. Replaces the single-method lambda call sites that
 * predate P2-130's two additive list methods on this port.
 */
public final class SingleMembershipRepository implements CurrentMembershipRepository {
    private final CurrentMembership membership;

    public SingleMembershipRepository(CurrentMembership membership) {
        this.membership = membership;
    }

    @Override
    public Optional<CurrentMembership> findCurrentActiveMembership(
            String issuer, String subject, WorkspaceId workspaceId) {
        return Optional.of(membership);
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
