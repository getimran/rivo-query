package com.knowimran.rivoquery.interfaceapi.support;

/**
 * Interface-boundary validation failure that generated OpenAPI/Bean
 * Validation does not catch (e.g. {@code minProperties}, {@code
 * dependentRequired} - see the P2-130 task's own note that generated Java
 * validation does not enforce either). Mapped to {@code 400 VALIDATION_FAILED}.
 */
public final class ValidationFailedException extends RuntimeException {
    public ValidationFailedException(String message) {
        super(message);
    }
}
