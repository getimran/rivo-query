package com.knowimran.rivoquery.platform.destination.dns;

import com.knowimran.rivoquery.platform.destination.DestinationDenialReason;
import java.util.Objects;

/**
 * Thrown by a {@link DnsResolver} for every fail-closed condition: an empty answer set, a
 * malformed or truncated response, a resolver timeout, an indeterminate (SERVFAIL-shaped) result,
 * or a CNAME chain exceeding the bounded depth. There is deliberately no permissive default path.
 */
public final class DnsResolutionException extends RuntimeException {
    private final DestinationDenialReason reason;

    public DnsResolutionException(DestinationDenialReason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public DestinationDenialReason reason() {
        return reason;
    }
}
