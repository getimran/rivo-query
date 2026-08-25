package com.knowimran.rivoquery.connection.application.lifecycle;

/**
 * Thrown when a requested connection does not exist in the actor's
 * workspace. Deliberately identical whether the id is entirely unknown or
 * belongs to a different tenant (the tenant-scoped repository query already
 * makes the two indistinguishable), avoiding a cross-tenant existence oracle.
 */
public final class ConnectionNotFoundException extends RuntimeException {
    public ConnectionNotFoundException() {
        super("connection was not found");
    }
}
