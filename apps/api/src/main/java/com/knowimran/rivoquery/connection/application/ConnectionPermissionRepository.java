package com.knowimran.rivoquery.connection.application;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionPermissionGrant;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.List;
import java.util.Optional;

public interface ConnectionPermissionRepository {
    Optional<ConnectionPermissionGrant> find(
            WorkspaceId workspaceId, ConnectionId connectionId, MembershipId membershipId);

    boolean replace(ConnectionPermissionGrant grant, long expectedCollectionVersion);

    boolean revoke(
            WorkspaceId workspaceId,
            ConnectionId connectionId,
            MembershipId membershipId,
            long expectedCollectionVersion);

    /**
     * A page of explicit grants for {@code connectionId}, ordered ascending
     * by membership id, strictly after {@code afterMembershipId} when
     * present. Added by P2-130 to back {@code GET .../permissions}; additive
     * to the P2-060 port.
     */
    List<ConnectionPermissionGrant> findPage(
            WorkspaceId workspaceId, ConnectionId connectionId,
            Optional<MembershipId> afterMembershipId, int limit);

    /** The connection's current permission-collection version, or {@code 0} if it has never had one. */
    long currentCollectionVersion(WorkspaceId workspaceId, ConnectionId connectionId);
}
