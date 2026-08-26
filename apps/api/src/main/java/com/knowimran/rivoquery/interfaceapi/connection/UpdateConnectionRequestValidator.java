package com.knowimran.rivoquery.interfaceapi.connection;

import com.knowimran.rivoquery.interfaceapi.generated.model.UpdateConnectionRequest;
import com.knowimran.rivoquery.interfaceapi.support.ValidationFailedException;

/**
 * Enforces the OpenAPI contract's {@code UpdateConnectionRequest.minProperties: 1}
 * rule - generated Java Bean Validation does not enforce JSON Schema {@code
 * minProperties}, so an all-null body would otherwise be silently accepted.
 *
 * <p>Also rejects a non-null {@code endpoint} outright (see {@link
 * ConnectionEndpointUpdateNotSupportedException}): {@link StructuredEndpoint}
 * is a syntax guard only (it accepts loopback/link-local/RFC1918 literals) -
 * unlike {@code createConnection}, which routes every endpoint through {@code
 * DestinationCapabilityAuthority#validate}, PATCH has no destination-policy
 * check at all, since {@code UpdateConnectionCommand} (the P2-100
 * lifecycle-saga command, off-limits to this fix) never sees the endpoint and
 * only bumps the connection's version. Persisting an unvalidated endpoint
 * into {@code connection_profiles} would be a primed SSRF for whenever a
 * future, properly-reviewed change threads endpoint updates through the
 * saga - so this rejects the field explicitly with a stable, honest
 * "not yet supported" signal rather than silently accepting and persisting
 * it unchecked.
 */
public final class UpdateConnectionRequestValidator {
    private UpdateConnectionRequestValidator() {}

    public static void requireNonEmpty(UpdateConnectionRequest request) {
        if (request.getName() == null
                && request.getEndpoint() == null
                && request.getTlsMode() == null
                && request.getConnectorOptions() == null
                && request.getObjectPolicy() == null
                && request.getLimits() == null) {
            throw new ValidationFailedException("at least one property must be supplied");
        }
    }

    /** Must be called before {@link #requireNonEmpty} would otherwise treat an endpoint-only body as valid. */
    public static void rejectEndpoint(UpdateConnectionRequest request) {
        if (request.getEndpoint() != null) {
            throw new ConnectionEndpointUpdateNotSupportedException();
        }
    }
}
