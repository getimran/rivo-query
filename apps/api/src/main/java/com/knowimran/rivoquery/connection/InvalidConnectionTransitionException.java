package com.knowimran.rivoquery.connection;

public final class InvalidConnectionTransitionException extends RuntimeException {
    public InvalidConnectionTransitionException() {
        super("connection state transition is not allowed");
    }
}
