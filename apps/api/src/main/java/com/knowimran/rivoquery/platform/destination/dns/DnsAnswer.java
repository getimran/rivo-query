package com.knowimran.rivoquery.platform.destination.dns;

import com.knowimran.rivoquery.connection.application.destination.AddressFamily;
import java.util.Arrays;
import java.util.Objects;

/**
 * A single raw resolver answer (post CNAME-chase) prior to policy classification. Deliberately
 * not a record so the address bytes are never exposed by an auto-generated accessor without
 * defensive copying (mirrors {@code VettedAddress}).
 */
public final class DnsAnswer {
    private final AddressFamily family;
    private final byte[] address;
    private final long ttlSeconds;

    public DnsAnswer(AddressFamily family, byte[] address, long ttlSeconds) {
        this.family = Objects.requireNonNull(family, "family");
        Objects.requireNonNull(address, "address");
        if (address.length != expectedLength(family)) {
            throw new IllegalArgumentException("address length does not match family");
        }
        if (ttlSeconds < 0) {
            throw new IllegalArgumentException("ttlSeconds must not be negative");
        }
        this.address = address.clone();
        this.ttlSeconds = ttlSeconds;
    }

    private static int expectedLength(AddressFamily family) {
        return switch (family) {
            case IPV4 -> 4;
            case IPV6 -> 16;
        };
    }

    public AddressFamily family() {
        return family;
    }

    public byte[] addressBytes() {
        return address.clone();
    }

    /** A DNS TTL of zero, or one the resolver could not vouch for, is never trustworthy. */
    public long ttlSeconds() {
        return ttlSeconds;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DnsAnswer that
                && family == that.family
                && ttlSeconds == that.ttlSeconds
                && Arrays.equals(address, that.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(family, ttlSeconds) * 31 + Arrays.hashCode(address);
    }

    @Override
    public String toString() {
        return "DnsAnswer[REDACTED]";
    }
}
