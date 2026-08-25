package com.knowimran.rivoquery.connection;

public record ConnectionVersion(long value) {
    public ConnectionVersion {
        if (value < 1) {
            throw new IllegalArgumentException("connection version must be positive");
        }
    }

    public ConnectionVersion next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("connection version exhausted");
        }
        return new ConnectionVersion(value + 1);
    }
}
