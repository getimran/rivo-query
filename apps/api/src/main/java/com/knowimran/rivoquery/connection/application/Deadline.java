package com.knowimran.rivoquery.connection.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record Deadline(Instant expiresAt) {
    public Deadline {
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean isExpired(Clock clock) {
        return !Objects.requireNonNull(clock, "clock").instant().isBefore(expiresAt);
    }

    public Duration remaining(Clock clock) {
        var remaining = Duration.between(Objects.requireNonNull(clock, "clock").instant(), expiresAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public void requireUsable(Clock clock) {
        if (isExpired(clock)) {
            throw new DeadlineExceededException();
        }
    }
}
