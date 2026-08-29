package com.knowimran.rivoquery.interfaceapi.schema;

public final class SchemaRefreshOperationNotFoundException extends RuntimeException {
    public SchemaRefreshOperationNotFoundException() {
        super("schema refresh operation was not found");
    }
}
