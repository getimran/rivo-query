package com.knowimran.rivoquery.catalog.application;

public final class SchemaRefreshFailedException extends RuntimeException {
    public SchemaRefreshFailedException(String message) {
        super(message);
    }

    public SchemaRefreshFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
