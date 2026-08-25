package com.knowimran.rivoquery.connection;

/**
 * Thrown when a write attempts to record a secret reference at a
 * (workspace, connection, version) that already has a durably stored
 * reference with different content. A single retry of the exact same write
 * is a legitimate idempotent no-op and does not throw this; only a genuine
 * conflict - a second, different secret object trying to reuse an
 * already-used version number - does.
 */
public final class ConflictingSecretReferenceException extends RuntimeException {
    public ConflictingSecretReferenceException() {
        super("a different secret reference is already recorded at this version");
    }
}
