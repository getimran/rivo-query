package com.knowimran.rivoquery.connection.application;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionPermissionGrant;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.WorkspaceId;
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
}
