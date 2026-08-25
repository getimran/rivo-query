package com.knowimran.rivoquery.platform.secrets;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only {@link Clock} whose {@link #instant()} can be advanced after
 * construction, so a single shared clock instance can mint a
 * grant/deadline at one instant and later observe it as expired without
 * sleeping the test thread.
 */
final class MutableClock extends Clock {
    private final AtomicReference<Instant> current;
    private final ZoneId zone;

    MutableClock(Instant initial, ZoneId zone) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    void advanceTo(Instant next) {
        current.set(Objects.requireNonNull(next, "next"));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(current.get(), newZone);
    }

    @Override
    public Instant instant() {
        return current.get();
    }
}
