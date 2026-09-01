package com.knowimran.rivoquery.interfaceapi.query;

public final class QueryFeedbackNotImplementedException extends RuntimeException {
    public QueryFeedbackNotImplementedException() {
        super("query feedback is not yet available");
    }
}
