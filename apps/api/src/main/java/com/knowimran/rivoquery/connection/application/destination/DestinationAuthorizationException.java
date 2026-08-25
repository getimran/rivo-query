package com.knowimran.rivoquery.connection.application.destination;

public final class DestinationAuthorizationException extends RuntimeException {
    public DestinationAuthorizationException() {
        super("destination capability is not authorized");
    }
}
