package com.knowimran.rivoquery.connection.application;

public final class DeadlineExceededException extends RuntimeException {
    public DeadlineExceededException() {
        super("operation deadline has expired");
    }
}
