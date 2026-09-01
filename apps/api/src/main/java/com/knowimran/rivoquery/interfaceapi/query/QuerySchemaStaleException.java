package com.knowimran.rivoquery.interfaceapi.query;

public final class QuerySchemaStaleException extends RuntimeException {
    public QuerySchemaStaleException() {
        super("schema metadata is not available for this connection");
    }
}
