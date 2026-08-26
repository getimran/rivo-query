package com.knowimran.rivoquery.interfaceapi.auth;

/**
 * Thrown for a missing, malformed, expired, or otherwise unverifiable bearer
 * token. The message is always this fixed, safe string - never the raw JWT,
 * a library parse-error message, or any claim value.
 */
public final class AuthenticationFailedException extends RuntimeException {
    public AuthenticationFailedException() {
        super("authentication is required");
    }
}
