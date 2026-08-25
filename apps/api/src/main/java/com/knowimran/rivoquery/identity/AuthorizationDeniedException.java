package com.knowimran.rivoquery.identity;

public final class AuthorizationDeniedException extends RuntimeException {
    public AuthorizationDeniedException() {
        super("operation is not authorized");
    }
}
