package com.knowimran.rivoquery.connection.application.lifecycle;

/**
 * Thrown by {@link ConnectionLifecycleService#test} and
 * {@link ConnectionLifecycleService#rotateSecret}: both operations must
 * validate a destination before ever touching a connector or promoting a
 * secret, but {@link com.knowimran.rivoquery.connection.DatabaseConnection}
 * has no persisted {@code endpoint} column yet (see the class Javadoc on
 * {@link ConnectionLifecycleService}), and a caller-supplied endpoint is
 * deliberately not accepted for either operation - see the P2-100
 * lifecycle-saga security review (B1): accepting one for {@code test} would
 * let any test-authorized caller redirect the connection's already-stored,
 * write-only plaintext secret to a destination they control and read it
 * back out via the connector's error/success signal, defeating the
 * write-only-secret invariant; accepting one for {@code rotateSecret} would
 * be an SSRF vector for the caller-supplied candidate material.
 *
 * <p>Both operations are therefore structurally blocked - not per-request
 * invalid, not a transient failure - until a reviewed V3 schema migration
 * persists a connection endpoint (and {@code test}/{@code rotateSecret} are
 * updated to read it from the connection's own stored state instead). No
 * external effect ever occurs before this exception is thrown.
 */
public final class ConnectionEndpointNotYetPersistedException extends RuntimeException {
    public ConnectionEndpointNotYetPersistedException() {
        super("connection endpoint is not yet persisted; test/rotate-secret against a "
                + "caller-chosen destination is not supported and this operation is "
                + "structurally blocked pending a V3 schema migration");
    }
}
