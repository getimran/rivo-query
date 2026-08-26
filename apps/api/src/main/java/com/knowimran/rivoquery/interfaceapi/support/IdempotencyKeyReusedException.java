package com.knowimran.rivoquery.interfaceapi.support;

/** Thrown when an {@code Idempotency-Key} is reused with a different request. */
public final class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException() {
        super("idempotency key was already used for a different request");
    }
}
