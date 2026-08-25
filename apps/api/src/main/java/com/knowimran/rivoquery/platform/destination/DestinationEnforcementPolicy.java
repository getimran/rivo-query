package com.knowimran.rivoquery.platform.destination;

import com.knowimran.rivoquery.connection.application.destination.DestinationAssessment;
import com.knowimran.rivoquery.connection.application.destination.DestinationPolicy;
import com.knowimran.rivoquery.connection.application.destination.DestinationRequest;
import com.knowimran.rivoquery.connection.application.destination.StructuredEndpoint;
import com.knowimran.rivoquery.connection.application.destination.VettedAddress;
import com.knowimran.rivoquery.platform.destination.dns.DnsAnswer;
import com.knowimran.rivoquery.platform.destination.dns.DnsResolutionException;
import com.knowimran.rivoquery.platform.destination.dns.DnsResolver;
import com.knowimran.rivoquery.platform.destination.policy.AddressCategory;
import com.knowimran.rivoquery.platform.destination.policy.AddressRangeClassifier;
import com.knowimran.rivoquery.platform.destination.policy.IpLiteral;
import com.knowimran.rivoquery.platform.destination.policy.OperatorPrivateAllowlist;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The real destination policy: bounded complete CNAME-plus-A/AAAA resolution, strict all-answer
 * classification (fail closed on any prohibited answer, including a mixed allowed/prohibited
 * set), the operator private allowlist, and earliest-trustworthy-TTL capability expiry with a
 * conservative fallback. Implements ADR-0013.
 *
 * <p>Every call performs a fresh, uncached resolution: this is what lets
 * {@code DestinationCapabilityAuthority.validate(...)} give every physical connection its own
 * current capability, and what lets pool revalidation detect a changed or expired binding.
 */
public final class DestinationEnforcementPolicy implements DestinationPolicy {
    private final DnsResolver dnsResolver;
    private final DestinationPolicyProperties properties;
    private final OperatorPrivateAllowlist allowlist;
    private final Clock clock;

    public DestinationEnforcementPolicy(
            DnsResolver dnsResolver, DestinationPolicyProperties properties, OperatorPrivateAllowlist allowlist, Clock clock) {
        this.dnsResolver = Objects.requireNonNull(dnsResolver, "dnsResolver");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public DestinationAssessment assess(DestinationRequest request) {
        Objects.requireNonNull(request, "request");
        StructuredEndpoint endpoint = request.endpoint();
        Instant now = clock.instant();
        Instant absoluteDeadline = earlierOf(now.plus(properties.dnsTimeout()), request.deadline().expiresAt());
        if (!now.isBefore(absoluteDeadline)) {
            throw new DestinationValidationException(
                    DestinationDenialReason.DEADLINE_EXCEEDED, "destination validation deadline is already exhausted");
        }

        String canonicalHostname;
        List<DnsAnswer> rawAnswers;
        var literal = IpLiteral.tryParse(endpoint.host());
        if (literal.isPresent()) {
            // A strict IP literal has no DNS/rebinding exposure; canonical form is just lower-case.
            canonicalHostname = endpoint.host().toLowerCase(Locale.ROOT);
            rawAnswers = List.of(new DnsAnswer(
                    literal.get().family(), literal.get().addressBytes(), properties.maxCapabilityTtlSeconds()));
        } else {
            canonicalHostname = canonicalizeHostname(endpoint.host());
            try {
                rawAnswers = dnsResolver.resolveAll(canonicalHostname, absoluteDeadline).answers();
            } catch (DnsResolutionException failedClosed) {
                throw new DestinationValidationException(failedClosed.reason(), "destination resolution failed closed");
            }
        }

        Set<VettedAddress> vetted = new LinkedHashSet<>();
        boolean anyPrivate = false;
        for (DnsAnswer answer : rawAnswers) {
            AddressCategory category = AddressRangeClassifier.classify(answer.family(), answer.addressBytes());
            if (category == AddressCategory.PROHIBITED) {
                // Fail closed on ANY prohibited answer, including a mixed allowed/prohibited set:
                // never silently choose the allowed answer while ignoring the prohibited one.
                throw new DestinationValidationException(
                        DestinationDenialReason.PROHIBITED_ADDRESS, "destination address is not permitted");
            }
            anyPrivate |= category == AddressCategory.PRIVATE;
            vetted.add(new VettedAddress(answer.family(), answer.addressBytes()));
        }
        if (anyPrivate
                && !allowlist.permits(request.databaseKind(), canonicalHostname, endpoint.port(), vetted)) {
            throw new DestinationValidationException(
                    DestinationDenialReason.PROHIBITED_ADDRESS, "destination address is not permitted");
        }

        long earliestTtlSeconds = rawAnswers.stream().mapToLong(DnsAnswer::ttlSeconds).min().orElseThrow();
        long boundedTtlSeconds = isTrustworthy(earliestTtlSeconds)
                ? Math.min(earliestTtlSeconds, properties.maxCapabilityTtlSeconds())
                : properties.fallbackTtlSeconds();
        // The capability expiry is bounded by the caller's overall remaining deadline, not by the
        // (much shorter) DNS-resolution-only budget above.
        Instant expiresAt = earlierOf(now.plusSeconds(boundedTtlSeconds), request.deadline().expiresAt());

        return new DestinationAssessment(
                canonicalHostname,
                endpoint.host(), // preserve the ORIGINAL host string exactly, for TLS identity
                endpoint.port(),
                vetted,
                fingerprintOf(vetted),
                properties.policyVersion(),
                now,
                expiresAt);
    }

    /** A TTL of zero (or one dnsjava could not vouch for) is never used to extend a capability. */
    private static boolean isTrustworthy(long ttlSeconds) {
        return ttlSeconds > 0;
    }

    private static String canonicalizeHostname(String host) {
        String ascii;
        try {
            ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException invalid) {
            throw new DestinationValidationException(
                    DestinationDenialReason.INVALID_ENDPOINT, "destination hostname is invalid");
        }
        return ascii.toLowerCase(Locale.ROOT);
    }

    private static String fingerprintOf(Set<VettedAddress> addresses) {
        List<String> sorted = addresses.stream()
                .map(address -> address.family() + ":" + HexFormat.of().formatHex(address.addressBytes()))
                .sorted()
                .toList();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String entry : sorted) {
                digest.update(entry.getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static Instant earlierOf(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }
}
