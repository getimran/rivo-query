package com.knowimran.rivoquery.interfaceapi.support;

/**
 * A concurrent request is already mid-flight for this exact {@code
 * (workspace, actor, Idempotency-Key)} - {@link IdempotencyGuard#reserve}
 * observed a row with {@code completed = false}. Distinct from {@link
 * IdempotencyKeyReusedException} (a key reused with a different request):
 * this is the *same* request racing itself (e.g. a client that retried
 * before its first attempt's response arrived), not a conflict, so the
 * client should back off briefly and retry rather than treat this as a
 * permanent error.
 */
public final class IdempotencyKeyInFlightException extends RuntimeException {
    public IdempotencyKeyInFlightException() {
        super("a request with this Idempotency-Key is already being processed");
    }
}
