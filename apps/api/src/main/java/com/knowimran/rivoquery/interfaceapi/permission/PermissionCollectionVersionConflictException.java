package com.knowimran.rivoquery.interfaceapi.permission;

/**
 * Thrown when {@code ConnectionPermissionRepository.replace}/{@code revoke}
 * report that the caller's {@code If-Match}-derived {@code
 * expectedCollectionVersion} no longer matches the connection's current
 * permission-collection version (an optimistic-concurrency conflict on the
 * shared per-connection grant collection, analogous to {@code
 * StaleConnectionVersionException} for the connection resource itself, but
 * for a distinct resource with its own version counter).
 */
public final class PermissionCollectionVersionConflictException extends RuntimeException {
    public PermissionCollectionVersionConflictException() {
        super("permission collection changed concurrently");
    }
}
