package com.knowimran.rivoquery.interfaceapi.schema;

public final class SchemaRefreshInFlightException extends RuntimeException {
    public SchemaRefreshInFlightException() {
        super("a schema refresh is already in flight for this connection");
    }
}
