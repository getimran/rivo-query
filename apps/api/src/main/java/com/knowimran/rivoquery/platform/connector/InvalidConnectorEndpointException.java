package com.knowimran.rivoquery.platform.connector;

/**
 * Thrown when a structured endpoint field (host, port, or database name) does not match the
 * closed, server-defined character allowlist enforced before a JDBC URL is built.
 *
 * <p>The exception message names only the fixed, known-safe field identifier that failed
 * validation (e.g. {@code "host"}); it never echoes the untrusted value itself, so this
 * exception is safe to log or surface without redaction.
 */
public final class InvalidConnectorEndpointException extends RuntimeException {

    public InvalidConnectorEndpointException(String field) {
        super("connector endpoint field is not trusted: " + field);
    }
}
