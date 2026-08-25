package com.knowimran.rivoquery.connection.application.secret;

public final class SecretAuthorizationException extends RuntimeException {
    public SecretAuthorizationException() {
        super("secret operation is not authorized");
    }
}
