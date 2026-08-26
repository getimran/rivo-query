package com.knowimran.rivoquery.identity.application;

import com.knowimran.rivoquery.identity.CurrentMembership;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.List;
import java.util.Optional;

public interface CurrentMembershipRepository {
    Optional<CurrentMembership> findCurrentActiveMembership(String issuer, String subject, WorkspaceId workspaceId);

    /**
     * Every active membership for the authenticated actor, across every
     * workspace. Backs the server-derived workspace-discovery operation
     * (PD2-002); added by P2-130, additive to the P2-050/P2-060 port.
     */
    List<MembershipDirectoryEntry> findActiveMembershipsForActor(String issuer, String subject);

    /**
     * A page of active memberships within {@code workspaceId}, ordered
     * ascending by membership id, strictly after {@code afterMembershipId}
     * when present. Backs the administrator-only membership-listing
     * operation; added by P2-130, additive to the P2-050/P2-060 port.
     */
    List<MembershipDirectoryEntry> findActiveMemberships(
            WorkspaceId workspaceId, Optional<MembershipId> afterMembershipId, int limit);

    /**
     * A single membership by its own id, scoped to {@code workspaceId} and
     * filtered to {@link com.knowimran.rivoquery.identity.MembershipStatus#ACTIVE}
     * only. Backs connection-permission-grant target validation (replace/
     * revoke): the OpenAPI contract requires that an unknown, inactive,
     * administrator, or foreign-workspace membership id all be
     * indistinguishable, so this method deliberately does not filter by
     * role - the caller decides whether an {@code ADMINISTRATOR}-role result
     * is eligible (it is not: MANAGE is derived from membership, never an
     * explicit grant). Added by P2-130, additive to the P2-050/P2-060 port.
     */
    Optional<MembershipDirectoryEntry> findActiveMembership(WorkspaceId workspaceId, MembershipId membershipId);
}
