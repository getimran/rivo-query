package com.knowimran.rivoquery.interfaceapi.support;

/**
 * Thrown for a missing, duplicated/multi-valued, comma-combined, malformed,
 * or non-canonical {@code X-RivoQuery-Workspace-Id} header (PD2-003 step 2).
 * Deliberately does not distinguish these cases in its message.
 */
public final class WorkspaceSelectorInvalidException extends RuntimeException {
    public WorkspaceSelectorInvalidException() {
        super("workspace selector header is missing, duplicated, or malformed");
    }
}
