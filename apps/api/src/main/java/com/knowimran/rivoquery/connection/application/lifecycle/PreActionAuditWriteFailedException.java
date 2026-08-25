package com.knowimran.rivoquery.connection.application.lifecycle;

/**
 * Thrown when the mandatory pre-action audit write fails. By construction,
 * every lifecycle operation performs this write before any state, secret,
 * DNS, or connector side effect, so this exception is proof that no such
 * side effect has occurred for the failed attempt. The message and cause
 * chain never carry secret material (the audit event itself is
 * metadata-only), but the underlying cause is intentionally not attached to
 * keep this exception's own shape stable and safe to map to a generic
 * problem-detail response.
 */
public final class PreActionAuditWriteFailedException extends RuntimeException {
    public PreActionAuditWriteFailedException() {
        super("pre-action audit could not be recorded; no action was attempted");
    }
}
