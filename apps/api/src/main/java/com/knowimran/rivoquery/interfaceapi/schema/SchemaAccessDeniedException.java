package com.knowimran.rivoquery.interfaceapi.schema;

public final class SchemaAccessDeniedException extends RuntimeException {
    public SchemaAccessDeniedException() {
        super("schema access denied");
    }
}
