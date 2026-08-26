package com.knowimran.rivoquery.interfaceapi.operation;

/** Unknown, foreign-tenant, and actor-mismatched operation ids are all indistinguishable. */
public final class OperationNotFoundException extends RuntimeException {
    public OperationNotFoundException() {
        super("operation was not found");
    }
}
