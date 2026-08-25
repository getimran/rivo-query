package com.knowimran.rivoquery.identity;

public enum GrantablePermission {
    VIEW_SCHEMA(ConnectionPermission.VIEW_SCHEMA),
    QUERY(ConnectionPermission.QUERY),
    EXPORT(ConnectionPermission.EXPORT);

    private final ConnectionPermission effectivePermission;

    GrantablePermission(ConnectionPermission effectivePermission) {
        this.effectivePermission = effectivePermission;
    }

    public ConnectionPermission effectivePermission() {
        return effectivePermission;
    }
}
