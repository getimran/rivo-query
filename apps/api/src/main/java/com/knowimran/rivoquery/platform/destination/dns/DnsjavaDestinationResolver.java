package com.knowimran.rivoquery.platform.destination.dns;

import com.knowimran.rivoquery.connection.application.destination.AddressFamily;
import com.knowimran.rivoquery.platform.destination.DestinationDenialReason;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

/**
 * Bounded, complete CNAME-plus-A/AAAA resolver over a raw dnsjava {@link Resolver}. Every
 * fail-closed condition in ADR-0013 (empty, malformed, truncated, timed-out, indeterminate, and a
 * CNAME chain deeper than the configured bound) is translated to a {@link DnsResolutionException};
 * there is no path that returns a permissive default.
 *
 * <p>This class performs a fresh query on every call; it never caches. It never mutates the
 * shared {@link Resolver}'s configured timeout: {@link Resolver} instances are typically
 * long-lived singletons shared across concurrent requests, and mutating a shared field per call
 * (e.g. {@code resolver.setTimeout(...)}) would let one caller's remaining budget race with and
 * override another caller's, weakening the "one absolute deadline, never reset" guarantee. Instead
 * each call bounds only its own wait via {@link java.util.concurrent.CompletableFuture#get(long,
 * TimeUnit)} on {@link Resolver#sendAsync(Message)}.
 */
public final class DnsjavaDestinationResolver implements DnsResolver {
    private static final Duration MAX_PER_QUERY_TIMEOUT = Duration.ofSeconds(5);

    private final Resolver resolver;
    private final int maxChainDepth;
    private final Clock clock;

    public DnsjavaDestinationResolver(Resolver resolver, int maxChainDepth, Clock clock) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        if (maxChainDepth < 1) {
            throw new IllegalArgumentException("maxChainDepth must be positive");
        }
        this.maxChainDepth = maxChainDepth;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public DnsResolution resolveAll(String hostname, Instant absoluteDeadline) {
        Objects.requireNonNull(hostname, "hostname");
        Objects.requireNonNull(absoluteDeadline, "absoluteDeadline");
        Name origin = parseName(hostname);
        var cnameChain = new LinkedHashSet<String>();
        var answers = new ArrayList<DnsAnswer>();
        resolveFamily(origin, Type.A, AddressFamily.IPV4, absoluteDeadline, cnameChain, answers);
        resolveFamily(origin, Type.AAAA, AddressFamily.IPV6, absoluteDeadline, cnameChain, answers);
        if (answers.isEmpty()) {
            throw new DnsResolutionException(
                    DestinationDenialReason.EMPTY_ANSWER, "destination name has no usable address records");
        }
        return new DnsResolution(List.copyOf(cnameChain), List.copyOf(answers));
    }

    private void resolveFamily(
            Name origin,
            int recordType,
            AddressFamily family,
            Instant absoluteDeadline,
            LinkedHashSet<String> cnameChain,
            List<DnsAnswer> answers) {
        Name currentName = origin;
        for (int hop = 0; hop < maxChainDepth; hop++) {
            Duration remaining = remainingBudget(absoluteDeadline);
            if (remaining.isZero() || remaining.isNegative()) {
                throw new DnsResolutionException(
                        DestinationDenialReason.RESOLVER_TIMEOUT,
                        "destination resolution exceeded its bounded deadline");
            }
            Message response = query(currentName, recordType, remaining);
            if (response.getHeader().getFlag(Flags.TC)) {
                throw new DnsResolutionException(
                        DestinationDenialReason.TRUNCATED_RESPONSE, "destination resolver response was truncated");
            }
            int rcode = response.getRcode();
            if (rcode == Rcode.NXDOMAIN) {
                return; // authoritative: name does not exist, no records of this type
            }
            if (rcode != Rcode.NOERROR) {
                throw new DnsResolutionException(
                        DestinationDenialReason.INDETERMINATE_RESPONSE,
                        "destination resolver returned an indeterminate result");
            }

            CNAMERecord cname = null;
            boolean foundTerminal = false;
            for (Record record : response.getSection(Section.ANSWER)) {
                if (!record.getName().equals(currentName)) {
                    continue;
                }
                if (record instanceof CNAMERecord cnameRecord) {
                    cname = cnameRecord;
                } else if (recordType == Type.A && record instanceof ARecord aRecord) {
                    // Raw wire rdata, never InetAddress: java.net.InetAddress.getByAddress(byte[16])
                    // silently collapses an IPv4-mapped 16-byte AAAA answer (::ffff:a.b.c.d) into an
                    // Inet4Address, so .getAddress() would return only 4 bytes for a genuinely
                    // 16-byte IPv6-family answer. rdataToWireCanonical() returns the exact address
                    // bytes as received on the wire, with no family-collapsing side effect.
                    answers.add(new DnsAnswer(family, aRecord.rdataToWireCanonical(), aRecord.getTTL()));
                    foundTerminal = true;
                } else if (recordType == Type.AAAA && record instanceof AAAARecord aaaaRecord) {
                    answers.add(new DnsAnswer(family, aaaaRecord.rdataToWireCanonical(), aaaaRecord.getTTL()));
                    foundTerminal = true;
                }
            }
            if (foundTerminal) {
                return;
            }
            if (cname == null) {
                return; // authoritative NOERROR with no data of this type (NODATA)
            }
            cnameChain.add(cname.getTarget().toString(true));
            currentName = cname.getTarget();
        }
        throw new DnsResolutionException(
                DestinationDenialReason.CHAIN_TOO_DEEP, "destination cname chain exceeded the bounded depth");
    }

    private Message query(Name name, int type, Duration timeout) {
        Record question = Record.newRecord(name, type, DClass.IN);
        long boundedMillis = Math.max(1L, boundedTimeout(timeout).toMillis());
        // Bound only THIS call's own wait, on the future it receives; never mutate the shared
        // Resolver's configured timeout (see the class Javadoc for why that would race).
        CompletionStage<Message> future = resolver.sendAsync(Message.newQuery(question));
        try {
            return future.toCompletableFuture().get(boundedMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            throw new DnsResolutionException(DestinationDenialReason.RESOLVER_TIMEOUT, "destination resolver timed out");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof SocketTimeoutException) {
                throw new DnsResolutionException(
                        DestinationDenialReason.RESOLVER_TIMEOUT, "destination resolver timed out");
            }
            throw new DnsResolutionException(
                    DestinationDenialReason.INDETERMINATE_RESPONSE, "destination resolver could not be reached");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DnsResolutionException(
                    DestinationDenialReason.RESOLVER_TIMEOUT, "destination resolver was interrupted before completing");
        }
    }

    private static Duration boundedTimeout(Duration remaining) {
        return remaining.compareTo(MAX_PER_QUERY_TIMEOUT) < 0 ? remaining : MAX_PER_QUERY_TIMEOUT;
    }

    private Duration remainingBudget(Instant absoluteDeadline) {
        Duration remaining = Duration.between(clock.instant(), absoluteDeadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    private static Name parseName(String hostname) {
        try {
            return Name.fromString(hostname, Name.root);
        } catch (TextParseException malformed) {
            throw new DnsResolutionException(
                    DestinationDenialReason.MALFORMED_RESPONSE, "destination hostname could not be parsed");
        }
    }
}
