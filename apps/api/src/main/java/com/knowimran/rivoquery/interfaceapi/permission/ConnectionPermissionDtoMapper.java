package com.knowimran.rivoquery.interfaceapi.permission;

import com.knowimran.rivoquery.connection.ConnectionPermissionGrant;
import com.knowimran.rivoquery.identity.GrantablePermission;
import com.knowimran.rivoquery.interfaceapi.generated.model.PermissionCollection;
import com.knowimran.rivoquery.interfaceapi.generated.model.PermissionGrant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Maps domain {@link ConnectionPermissionGrant}s to the REST {@code PermissionCollection} DTO. */
public final class ConnectionPermissionDtoMapper {
    private ConnectionPermissionDtoMapper() {}

    public static PermissionCollection toCollection(long version, List<ConnectionPermissionGrant> page) {
        List<PermissionGrant> items = page.stream().map(ConnectionPermissionDtoMapper::toGrant).toList();
        return new PermissionCollection(version, items);
    }

    private static PermissionGrant toGrant(ConnectionPermissionGrant grant) {
        return new PermissionGrant(grant.membershipId().value(), toDto(grant.permissions()));
    }

    private static Set<com.knowimran.rivoquery.interfaceapi.generated.model.GrantablePermission> toDto(
            Set<GrantablePermission> permissions) {
        Set<com.knowimran.rivoquery.interfaceapi.generated.model.GrantablePermission> mapped = new LinkedHashSet<>();
        permissions.forEach(permission -> mapped.add(
                com.knowimran.rivoquery.interfaceapi.generated.model.GrantablePermission.valueOf(permission.name())));
        return mapped;
    }

    public static Set<GrantablePermission> toDomain(
            Set<com.knowimran.rivoquery.interfaceapi.generated.model.GrantablePermission> permissions) {
        Set<GrantablePermission> mapped = new LinkedHashSet<>();
        permissions.forEach(permission -> mapped.add(GrantablePermission.valueOf(permission.name())));
        return mapped;
    }
}
