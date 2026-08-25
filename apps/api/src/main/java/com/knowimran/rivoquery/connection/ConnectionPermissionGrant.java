package com.knowimran.rivoquery.connection;

import com.knowimran.rivoquery.identity.GrantablePermission;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Objects;
import java.util.Set;

public record ConnectionPermissionGrant(
        WorkspaceId workspaceId,
        ConnectionId connectionId,
        MembershipId membershipId,
        Set<GrantablePermission> permissions,
        long version) {
    public ConnectionPermissionGrant {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(membershipId, "membershipId");
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        if (permissions.isEmpty()) {
            throw new IllegalArgumentException("permission grant must not be empty");
        }
        if (version < 1) {
            throw new IllegalArgumentException("permission version must be positive");
        }
    }
}
