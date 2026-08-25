package com.knowimran.rivoquery.connection.application.lifecycle;

import com.knowimran.rivoquery.connection.connector.ConnectorOutcomeCode;
import java.util.Objects;

/**
 * Thrown when a {@link com.knowimran.rivoquery.connection.connector.DatabaseConnector} test
 * (non-pooled connectivity check) does not succeed. Carries only the closed,
 * safe {@link ConnectorOutcomeCode}; never a driver message or stack trace.
 */
public final class ConnectorTestFailedException extends RuntimeException {
    private final ConnectorOutcomeCode outcomeCode;

    public ConnectorTestFailedException(ConnectorOutcomeCode outcomeCode) {
        super("connection test did not succeed");
        this.outcomeCode = Objects.requireNonNull(outcomeCode, "outcomeCode");
    }

    public ConnectorOutcomeCode outcomeCode() {
        return outcomeCode;
    }
}
