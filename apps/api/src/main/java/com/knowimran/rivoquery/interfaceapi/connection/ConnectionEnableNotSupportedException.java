package com.knowimran.rivoquery.interfaceapi.connection;

/**
 * {@code enableConnection} is declared by the OpenAPI contract but {@code
 * ConnectionLifecycleService} deliberately does not implement it (P2-100's
 * own acceptance list omits it; see docs/IMPLEMENTATION_TASKS.md). This
 * controller still authenticates, validates the workspace selector,
 * authorizes (administrator-only), and looks up the connection exactly like
 * every other connection endpoint (so a cross-tenant/unknown id is still
 * indistinguishable {@code CONNECTION_NOT_FOUND}) before failing closed with
 * this stable, honest "not yet supported" signal - never a generic 500 and
 * never a silent success.
 */
public final class ConnectionEnableNotSupportedException extends RuntimeException {
    public ConnectionEnableNotSupportedException() {
        super("re-enabling a connection is not yet supported");
    }
}
