package com.knowimran.rivoquery.connection.application.lifecycle;

import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.application.DurableOperation;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of a successful mutation. {@code operation} is present for every
 * action that has a durable-operation shape (create, test, rotate-secret,
 * delete); enable/disable/update are today purely synchronous local state
 * transitions with no durable-operation row (see
 * {@link com.knowimran.rivoquery.connection.application.OperationAction}, which has no
 * ENABLE/DISABLE member), so {@code operation} is empty for those.
 */
public record ConnectionLifecycleResult(DatabaseConnection connection, Optional<DurableOperation> operation) {
    public ConnectionLifecycleResult {
        Objects.requireNonNull(connection, "connection");
        operation = Objects.requireNonNull(operation, "operation");
    }
}
