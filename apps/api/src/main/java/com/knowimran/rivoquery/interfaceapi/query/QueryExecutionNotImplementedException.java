package com.knowimran.rivoquery.interfaceapi.query;

public final class QueryExecutionNotImplementedException extends RuntimeException {
    public QueryExecutionNotImplementedException() {
        super("query execution is not yet available");
    }
}
