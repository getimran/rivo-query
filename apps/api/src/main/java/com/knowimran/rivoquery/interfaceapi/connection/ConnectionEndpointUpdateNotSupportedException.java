package com.knowimran.rivoquery.interfaceapi.connection;

/**
 * {@code PATCH /connections/{id}} declares an {@code endpoint} field (the
 * OpenAPI {@code UpdateConnectionRequest} schema mirrors {@code
 * CreateConnectionRequest}'s shape), but unlike {@code createConnection} -
 * which routes every endpoint through {@code
 * DestinationCapabilityAuthority#validate} before it can be persisted or
 * used - the P2-100 {@code UpdateConnectionCommand} lifecycle-saga command
 * never sees the endpoint at all (it only bumps the connection's version).
 * {@link com.knowimran.rivoquery.connection.application.destination.StructuredEndpoint}
 * is a syntax guard only: it deliberately accepts loopback/link-local/RFC1918
 * IP literals (e.g. {@code 169.254.169.254}, a cloud metadata address) that
 * only {@code DestinationPolicy} rejects. Accepting a caller-supplied
 * endpoint here and writing it straight into {@code connection_profiles}
 * (bypassing that policy check) would be a primed SSRF for whenever a future
 * schema/saga change makes {@code test}/{@code rotate-secret} reachable
 * again.
 *
 * <p>Threading real destination validation through the update saga is a
 * genuine follow-up (needs its own reviewed design - {@code
 * connection.application.lifecycle} is off-limits to this fix). Until then,
 * this is a stable, honest, client-correctable "not yet supported" failure -
 * the same pattern {@link ConnectionEnableNotSupportedException}/{@code
 * ConnectionEndpointNotYetPersistedException} already establish for
 * {@code enable}/{@code test}/{@code rotate-secret} - rather than silently
 * accepting and persisting an unvalidated endpoint.
 */
public final class ConnectionEndpointUpdateNotSupportedException extends RuntimeException {
    public ConnectionEndpointUpdateNotSupportedException() {
        super("updating a connection's endpoint is not yet supported; omit the endpoint field");
    }
}
