package com.knowimran.rivoquery.connection.connector;

import java.util.Objects;

public record ConnectorResult(boolean successful, ConnectorOutcomeCode safeCode) {
    public ConnectorResult {
        Objects.requireNonNull(safeCode, "safeCode");
        if (successful != safeCode.isSuccessful()) {
            throw new IllegalArgumentException("connector outcome is inconsistent");
        }
    }
}
