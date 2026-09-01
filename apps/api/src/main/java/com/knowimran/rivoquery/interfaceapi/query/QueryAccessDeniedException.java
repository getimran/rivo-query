package com.knowimran.rivoquery.interfaceapi.query;

public final class QueryAccessDeniedException extends RuntimeException {
    public QueryAccessDeniedException() {
        super("query access denied");
    }
}
