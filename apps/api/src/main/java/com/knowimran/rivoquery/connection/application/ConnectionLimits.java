package com.knowimran.rivoquery.connection.application;

/** Mirrors the OpenAPI {@code ConnectionLimits} schema bounds. */
public record ConnectionLimits(int statementTimeoutMs, int maxRows) {
    public ConnectionLimits {
        if (statementTimeoutMs < 100 || statementTimeoutMs > 60_000) {
            throw new IllegalArgumentException("statementTimeoutMs must be between 100 and 60000");
        }
        if (maxRows < 1 || maxRows > 1000) {
            throw new IllegalArgumentException("maxRows must be between 1 and 1000");
        }
    }

    public static ConnectionLimits defaults() {
        return new ConnectionLimits(15_000, 200);
    }
}
