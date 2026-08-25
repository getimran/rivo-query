package com.knowimran.rivoquery.platform.destination.policy;

import com.knowimran.rivoquery.connection.application.destination.AddressFamily;
import java.util.Arrays;
import java.util.List;

/**
 * Strict, non-overridable classification of a single resolved address per ADR-0013: unspecified,
 * loopback, link-local, multicast, broadcast, documentation, benchmarking, reserved/special-use,
 * and cloud-metadata ranges are always {@link AddressCategory#PROHIBITED}. RFC 1918, carrier-grade
 * NAT, and IPv6 unique-local are {@link AddressCategory#PRIVATE} (operator-allowlistable).
 * Everything else is {@link AddressCategory#PUBLIC}.
 *
 * <p>IPv4-mapped and other well-known IPv6 transition forms are classified by their embedded IPv4
 * address, never by the wrapping IPv6 prefix alone.
 */
public final class AddressRangeClassifier {
    private record Rule(CidrRange range, AddressCategory category) {}

    private static final List<Rule> RULES = List.of(
            rule("0.0.0.0/8", AddressCategory.PROHIBITED), // "this network" / unspecified
            rule("127.0.0.0/8", AddressCategory.PROHIBITED), // loopback
            rule("169.254.0.0/16", AddressCategory.PROHIBITED), // link-local, incl. cloud metadata
            rule("192.0.0.0/24", AddressCategory.PROHIBITED), // IETF protocol assignments
            rule("192.0.2.0/24", AddressCategory.PROHIBITED), // documentation (TEST-NET-1)
            rule("198.51.100.0/24", AddressCategory.PROHIBITED), // documentation (TEST-NET-2)
            rule("203.0.113.0/24", AddressCategory.PROHIBITED), // documentation (TEST-NET-3)
            rule("198.18.0.0/15", AddressCategory.PROHIBITED), // benchmarking
            rule("192.88.99.0/24", AddressCategory.PROHIBITED), // deprecated 6to4 relay anycast
            rule("224.0.0.0/4", AddressCategory.PROHIBITED), // multicast
            rule("240.0.0.0/4", AddressCategory.PROHIBITED), // reserved, incl. broadcast
            rule("100.64.0.0/10", AddressCategory.PRIVATE), // carrier-grade NAT
            rule("10.0.0.0/8", AddressCategory.PRIVATE),
            rule("172.16.0.0/12", AddressCategory.PRIVATE),
            rule("192.168.0.0/16", AddressCategory.PRIVATE),
            rule("::/128", AddressCategory.PROHIBITED), // unspecified
            rule("::1/128", AddressCategory.PROHIBITED), // loopback
            rule("fe80::/10", AddressCategory.PROHIBITED), // link-local
            rule("ff00::/8", AddressCategory.PROHIBITED), // multicast
            rule("2001:db8::/32", AddressCategory.PROHIBITED), // documentation
            rule("2001:2::/48", AddressCategory.PROHIBITED), // benchmarking (IPv6 counterpart of 198.18.0.0/15)
            rule("64:ff9b:1::/48", AddressCategory.PROHIBITED), // RFC 8215 local-use NAT64
            rule("100::/64", AddressCategory.PROHIBITED), // discard-only
            rule("2001::/32", AddressCategory.PROHIBITED), // Teredo
            rule("fec0::/10", AddressCategory.PROHIBITED), // deprecated site-local
            rule("2001:10::/28", AddressCategory.PROHIBITED), // ORCHID
            rule("2001:20::/28", AddressCategory.PROHIBITED), // ORCHIDv2
            rule("3fff::/20", AddressCategory.PROHIBITED), // RFC 9637 documentation
            rule("5f00::/16", AddressCategory.PROHIBITED), // SRv6 SIDs
            rule("fc00::/7", AddressCategory.PRIVATE)); // unique-local

    private AddressRangeClassifier() {}

    public static AddressCategory classify(AddressFamily family, byte[] address) {
        if (family == AddressFamily.IPV4) {
            return classifyIpv4(address);
        }
        byte[] embeddedIpv4 = extractEmbeddedIpv4(address);
        if (embeddedIpv4 != null) {
            return classifyIpv4(embeddedIpv4);
        }
        for (Rule rule : RULES) {
            if (rule.range().contains(AddressFamily.IPV6, address)) {
                return rule.category();
            }
        }
        return AddressCategory.PUBLIC;
    }

    private static AddressCategory classifyIpv4(byte[] address) {
        for (Rule rule : RULES) {
            if (rule.range().contains(AddressFamily.IPV4, address)) {
                return rule.category();
            }
        }
        return AddressCategory.PUBLIC;
    }

    /** IPv4-mapped ({@code ::ffff:0:0/96}), deprecated IPv4-compatible, 6to4, and NAT64. */
    private static byte[] extractEmbeddedIpv4(byte[] address) {
        boolean firstTenZero = true;
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) {
                firstTenZero = false;
                break;
            }
        }
        if (firstTenZero && (address[10] & 0xFF) == 0xFF && (address[11] & 0xFF) == 0xFF) {
            return Arrays.copyOfRange(address, 12, 16); // IPv4-mapped ::ffff:a.b.c.d
        }
        boolean firstTwelveZero = firstTenZero && address[10] == 0 && address[11] == 0;
        boolean allZero =
                firstTwelveZero && address[12] == 0 && address[13] == 0 && address[14] == 0 && address[15] == 0;
        boolean isLoopback =
                firstTwelveZero && address[12] == 0 && address[13] == 0 && address[14] == 0 && address[15] == 1;
        if (firstTwelveZero && !allZero && !isLoopback) {
            return Arrays.copyOfRange(address, 12, 16); // deprecated IPv4-compatible ::a.b.c.d
        }
        if ((address[0] & 0xFF) == 0x20 && (address[1] & 0xFF) == 0x02) {
            return Arrays.copyOfRange(address, 2, 6); // 6to4, 2002:AABB:CCDD::/48 embeds a.b.c.d
        }
        boolean nat64Prefix = (address[0] & 0xFF) == 0x00
                && (address[1] & 0xFF) == 0x64
                && (address[2] & 0xFF) == 0xFF
                && (address[3] & 0xFF) == 0x9B
                && address[4] == 0
                && address[5] == 0
                && address[6] == 0
                && address[7] == 0
                && address[8] == 0
                && address[9] == 0
                && address[10] == 0
                && address[11] == 0;
        if (nat64Prefix) {
            return Arrays.copyOfRange(address, 12, 16); // NAT64 well-known prefix 64:ff9b::/96
        }
        return null;
    }

    private static Rule rule(String cidr, AddressCategory category) {
        return new Rule(CidrRange.parse(cidr), category);
    }
}
