package com.knowimran.rivoquery.connection;

public final class StaleConnectionVersionException extends RuntimeException {
    public StaleConnectionVersionException() {
        super("connection version is stale");
    }
}
