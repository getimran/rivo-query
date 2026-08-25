package com.knowimran.rivoquery.platform.destination.dns;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Deterministic, scriptable {@link DnsResolver} test double. No real DNS traffic; used by every
 * destination-policy test that needs to change DNS answers between calls (registration, pool use,
 * and each physical connection).
 */
public final class FakeDnsResolver implements DnsResolver {
    private final Deque<Function<String, DnsResolution>> scripted = new ArrayDeque<>();
    private final List<String> requestedHostnames = new ArrayList<>();
    private final List<Instant> requestedDeadlines = new ArrayList<>();
    private Function<String, DnsResolution> defaultBehavior;

    public static FakeDnsResolver alwaysReturning(DnsResolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        FakeDnsResolver fake = new FakeDnsResolver();
        fake.defaultBehavior = hostname -> resolution;
        return fake;
    }

    public void setDefault(DnsResolution resolution) {
        defaultBehavior = hostname -> resolution;
    }

    public void enqueue(DnsResolution resolution) {
        scripted.add(hostname -> resolution);
    }

    public void enqueueFailure(DnsResolutionException exception) {
        scripted.add(hostname -> {
            throw exception;
        });
    }

    @Override
    public DnsResolution resolveAll(String hostname, Instant absoluteDeadline) {
        requestedHostnames.add(hostname);
        requestedDeadlines.add(absoluteDeadline);
        Function<String, DnsResolution> next = scripted.poll();
        if (next != null) {
            return next.apply(hostname);
        }
        if (defaultBehavior != null) {
            return defaultBehavior.apply(hostname);
        }
        throw new IllegalStateException("FakeDnsResolver has no scripted response for " + hostname);
    }

    public List<String> requestedHostnames() {
        return List.copyOf(requestedHostnames);
    }

    public int requestCount() {
        return requestedHostnames.size();
    }
}
