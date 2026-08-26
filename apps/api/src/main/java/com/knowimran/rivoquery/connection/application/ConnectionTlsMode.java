package com.knowimran.rivoquery.connection.application;

/**
 * Domain mirror of the OpenAPI {@code TlsMode} enum, kept in {@code
 * connection.application} rather than depending on the generated REST model
 * (application code must not depend on {@code interfaceapi.generated}; see
 * {@code ArchitectureTest}).
 */
public enum ConnectionTlsMode {
    VERIFY_FULL,
    DISABLED
}
