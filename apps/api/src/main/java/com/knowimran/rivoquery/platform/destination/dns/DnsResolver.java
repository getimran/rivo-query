package com.knowimran.rivoquery.platform.destination.dns;

import java.time.Instant;

/**
 * Performs a fresh, bounded, complete CNAME-plus-A/AAAA resolution of a hostname, returning every
 * answer (never just the first). Implementations must never cache results across calls: ADR-0013
 * requires a fresh lookup before every connection test and before every new physical connection.
 *
 * <p>{@code absoluteDeadline} is a single absolute instant, not a per-call relative timeout:
 * callers compute it once as {@code min(platform DNS timeout cap, caller's remaining deadline)}
 * so that internal retries (e.g. a CNAME hop, or the second of the A/AAAA pair) can never reset or
 * extend the budget.
 */
public interface DnsResolver {
    /**
     * @throws DnsResolutionException on any fail-closed condition (empty, malformed, truncated,
     *     timed-out, indeterminate, or a CNAME chain deeper than the bounded maximum).
     */
    DnsResolution resolveAll(String hostname, Instant absoluteDeadline);
}
