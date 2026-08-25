package com.knowimran.rivoquery.connection.connector;

public enum ConnectorOutcomeCode {
    CONNECTION_TEST_SUCCEEDED(true),
    CONNECTION_TEST_FAILED(false),
    DESTINATION_NOT_ALLOWED(false),
    DESTINATION_RESOLUTION_FAILED(false),
    SECRET_PROVIDER_UNAVAILABLE(false),
    DEADLINE_EXCEEDED(false);

    private final boolean successful;

    ConnectorOutcomeCode(boolean successful) {
        this.successful = successful;
    }

    boolean isSuccessful() {
        return successful;
    }
}
