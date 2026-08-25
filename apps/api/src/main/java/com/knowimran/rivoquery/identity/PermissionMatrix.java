package com.knowimran.rivoquery.identity;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class PermissionMatrix {
    private static final Set<ConnectionPermission> ADMINISTRATOR_PERMISSIONS =
            Set.copyOf(EnumSet.allOf(ConnectionPermission.class));

    private PermissionMatrix() {}

    public static Set<ConnectionPermission> effectivePermissions(
            ActorContext actor,
            WorkspaceId grantWorkspace,
            MembershipId grantee,
            Set<GrantablePermission> granted) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(grantWorkspace, "grantWorkspace");
        actor.requireWorkspace(grantWorkspace);
        if (actor.role() == WorkspaceRole.ADMINISTRATOR) {
            return ADMINISTRATOR_PERMISSIONS;
        }
        if (!actor.workspaceId().equals(grantWorkspace)
                || !actor.membershipId().equals(grantee)) {
            return Set.of();
        }
        var effective = EnumSet.noneOf(ConnectionPermission.class);
        Set.copyOf(Objects.requireNonNull(granted, "granted")).stream()
                .map(GrantablePermission::effectivePermission)
                .forEach(effective::add);
        return Set.copyOf(effective);
    }

    public static boolean allows(
            ActorContext actor,
            WorkspaceId grantWorkspace,
            MembershipId grantee,
            Set<GrantablePermission> granted,
            ConnectionPermission required) {
        return effectivePermissions(actor, grantWorkspace, grantee, granted)
                .contains(Objects.requireNonNull(required, "required"));
    }
}
