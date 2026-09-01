package com.knowimran.rivoquery.interfaceapi.query;

public final class QueryProviderException extends RuntimeException {
    public QueryProviderException() {
        super("model provider unavailable");
    }
}
