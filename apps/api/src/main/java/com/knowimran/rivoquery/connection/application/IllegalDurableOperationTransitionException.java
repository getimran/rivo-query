package com.knowimran.rivoquery.connection.application;

/**
 * Thrown when a {@link DurableOperationRepository#save} write would rewrite
 * an already-terminal durable operation (a state that is not a legal
 * progression), reattribute it to a different actor, or un-cancel it. A
 * byte-for-byte identical rewrite of the stored row is still accepted as an
 * idempotent retry and does not throw this.
 */
public final class IllegalDurableOperationTransitionException extends RuntimeException {
    public IllegalDurableOperationTransitionException() {
        super("durable operation write is not a legal progression from its stored state");
    }
}
