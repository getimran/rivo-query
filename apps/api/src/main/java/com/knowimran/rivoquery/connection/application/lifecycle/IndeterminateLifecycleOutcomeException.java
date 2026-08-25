package com.knowimran.rivoquery.connection.application.lifecycle;

import com.knowimran.rivoquery.connection.OperationId;
import java.util.Objects;
import java.util.Optional;

/**
 * Thrown when an external effect (a connection-state write, a secret-store
 * mutation) has already happened but this attempt could not durably confirm
 * its own terminal outcome (the terminal audit write failed, or a
 * best-effort cleanup step - e.g. secret revocation during delete - did not
 * complete). The caller must not treat this as "nothing happened" (it would
 * be unsafe to blindly retry the same effect) nor as a definite success.
 *
 * <p>When present, {@link #operationId()} identifies the durable operation
 * record that remains reconcilable: a future reconciliation pass can inspect
 * its state and the connection's own state to decide what, if anything,
 * still needs to happen, without re-running an effect that already
 * completed. It is empty only for operations that never persist a {@code
 * DurableOperation} row in the first place ({@code disable}/{@code update}
 * today - see {@code ConnectionLifecycleService}'s class Javadoc: {@code
 * OperationAction} has no DISABLE member, and {@code update} has no
 * persisted field to reconcile), for which the only reconciliation step is
 * re-reading the connection itself and re-checking its ETag - safe and
 * idempotent by construction, so there is nothing an {@code OperationId}
 * could add.
 */
public final class IndeterminateLifecycleOutcomeException extends RuntimeException {
    private final Optional<OperationId> operationId;

    public IndeterminateLifecycleOutcomeException(OperationId operationId) {
        this(Optional.of(Objects.requireNonNull(operationId, "operationId")));
    }

    private IndeterminateLifecycleOutcomeException(Optional<OperationId> operationId) {
        super("outcome could not be confirmed; the operation is reconcilable and must not be retried blindly");
        this.operationId = operationId;
    }

    /**
     * For an operation that never persists a {@code DurableOperation} row
     * (see class Javadoc). There is deliberately no reconcilable
     * {@link OperationId} to carry.
     */
    public static IndeterminateLifecycleOutcomeException withoutReconcilableOperation() {
        return new IndeterminateLifecycleOutcomeException(Optional.empty());
    }

    public Optional<OperationId> operationId() {
        return operationId;
    }
}
