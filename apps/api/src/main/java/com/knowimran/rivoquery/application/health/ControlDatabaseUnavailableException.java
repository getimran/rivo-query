package com.knowimran.rivoquery.application.health;

public final class ControlDatabaseUnavailableException extends RuntimeException {

    public ControlDatabaseUnavailableException() {
        super("Control database readiness check failed");
    }

    public ControlDatabaseUnavailableException(Throwable cause) {
        super("Control database readiness check failed", cause);
    }
}
