package com.knowimran.rivoquery.platform.destination;

import java.util.Objects;

/**
 * Thrown by {@link DestinationEnforcementPolicy} whenever a destination cannot be fail-closed
 * vetted. The message is a safe, static description; it never includes raw resolver/network
 * detail.
 */
public final class DestinationValidationException extends RuntimeException {
    private final DestinationDenialReason reason;

    public DestinationValidationException(DestinationDenialReason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public DestinationDenialReason reason() {
        return reason;
    }
}
