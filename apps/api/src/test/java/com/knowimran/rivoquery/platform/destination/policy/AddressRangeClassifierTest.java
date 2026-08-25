package com.knowimran.rivoquery.platform.destination.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowimran.rivoquery.connection.application.destination.AddressFamily;
import org.junit.jupiter.api.Test;

class AddressRangeClassifierTest {

    @Test
    void classifiesProhibitedIpv4Ranges() {
        assertThat(classifyV4("0.0.0.0")).isEqualTo(AddressCategory.PROHIBITED);
        assertThat(classifyV4("127.0.0.1")).isEqualTo(AddressCategory.PROHIBITED);
        assertThat(classifyV4("169.254.169.254")).isEqualTo(AddressCategory.PROHIBITED); // cloud metadata
        assertThat(classifyV4("192.0.2.10")).isEqualTo(AddressCategory.PROHIBITED); // documentation
        assertThat(classifyV4("198.51.100.10")).isEqualTo(AddressCategory.PROHIBITED);
        assertThat(classifyV4("203.0.113.10")).isEqualTo(AddressCategory.PROHIBITED);
        assertThat(classifyV4("198.18.0.5")).isEqualTo(AddressCategory.PROHIBITED); // benchmarking
        assertThat(classifyV4("224.0.0.1")).isEqualTo(AddressCategory.PROHIBITED); // multicast
        assertThat(classifyV4("255.255.255.255")).isEqualTo(AddressCategory.PROHIBITED); // broadcast
    }

    @Test
    void classifiesPrivateIpv4RangesAsOverridable() {
        assertThat(classifyV4("10.0.0.1")).isEqualTo(AddressCategory.PRIVATE);
        assertThat(classifyV4("172.16.0.1")).isEqualTo(AddressCategory.PRIVATE);
        assertThat(classifyV4("192.168.1.1")).isEqualTo(AddressCategory.PRIVATE);
        assertThat(classifyV4("100.64.0.1")).isEqualTo(AddressCategory.PRIVATE); // carrier-grade NAT
    }

    @Test
    void classifiesOrdinaryPublicIpv4AsPublic() {
        assertThat(classifyV4("93.184.216.34")).isEqualTo(AddressCategory.PUBLIC);
        assertThat(classifyV4("8.8.8.8")).isEqualTo(AddressCategory.PUBLIC);
    }

    @Test
    void classifiesProhibitedAndPrivateIpv6Ranges() {
        assertThat(classifyV6("::")).isEqualTo(AddressCategory.PROHIBITED);
        assertThat(classifyV6("::1")).isEqualTo(AddressCategory.PROHIBITED);
        assertThat(classifyV6("fe80::1")).isEqualTo(AddressCategory.PROHIBITED);
        assertThat(classifyV6("ff02::1")).isEqualTo(AddressCategory.PROHIBITED); // multicast
        assertThat(classifyV6("2001:db8::1")).isEqualTo(AddressCategory.PROHIBITED); // documentation
        assertThat(classifyV6("fd00::1")).isEqualTo(AddressCategory.PRIVATE); // unique-local
    }

    @Test
    void classifiesOrdinaryPublicIpv6AsPublic() {
        assertThat(classifyV6("2606:4700:4700::1111")).isEqualTo(AddressCategory.PUBLIC);
    }

    /**
     * Regression for a prior review finding: several IPv6 ranges ADR-0013 and this class's own
     * Javadoc name as non-overridable-deny were previously missing from the rule table and fell
     * through to the default PUBLIC classification.
     */
    @Test
    void classifiesPreviouslyMissingProhibitedIpv6Ranges() {
        // RFC 8215 local-use NAT64: distinct from the well-known 64:ff9b::/96 prefix, which is
        // handled separately via embedded-IPv4 classification.
        assertThat(classifyV6("64:ff9b:1::1")).isEqualTo(AddressCategory.PROHIBITED);
        assertThat(classifyV6("64:ff9b:1::7f00:1")).isEqualTo(AddressCategory.PROHIBITED); // wraps loopback
        assertThat(classifyV6("2001:2::1")).isEqualTo(AddressCategory.PROHIBITED); // IPv6 benchmarking
        assertThat(classifyV6("2001::1")).isEqualTo(AddressCategory.PROHIBITED); // Teredo
        assertThat(classifyV6("fec0::1")).isEqualTo(AddressCategory.PROHIBITED); // deprecated site-local
        assertThat(classifyV6("2001:10::1")).isEqualTo(AddressCategory.PROHIBITED); // ORCHID
        assertThat(classifyV6("2001:20::1")).isEqualTo(AddressCategory.PROHIBITED); // ORCHIDv2
        assertThat(classifyV6("3fff::1")).isEqualTo(AddressCategory.PROHIBITED); // RFC 9637 documentation
        assertThat(classifyV6("5f00::1")).isEqualTo(AddressCategory.PROHIBITED); // SRv6 SIDs
    }

    @Test
    void classifiesTransitionFormsByTheirEmbeddedIpv4Address() {
        // IPv4-mapped: wraps a loopback address, must still be prohibited.
        assertThat(classifyV6("::ffff:127.0.0.1")).isEqualTo(AddressCategory.PROHIBITED);
        // IPv4-mapped: wraps a private address, must still be private (overridable), not public.
        assertThat(classifyV6("::ffff:10.1.2.3")).isEqualTo(AddressCategory.PRIVATE);
        // IPv4-mapped: wraps an ordinary public address.
        assertThat(classifyV6("::ffff:93.184.216.34")).isEqualTo(AddressCategory.PUBLIC);
        // 6to4 embeds the IPv4 address in bytes 2-5; here it wraps loopback.
        assertThat(classifyV6("2002:7f00:0001::")).isEqualTo(AddressCategory.PROHIBITED);
        // NAT64 well-known prefix embeds the IPv4 address in bytes 12-15; here it wraps loopback.
        assertThat(classifyV6("64:ff9b::7f00:1")).isEqualTo(AddressCategory.PROHIBITED);
    }

    private static AddressCategory classifyV4(String literal) {
        return AddressRangeClassifier.classify(AddressFamily.IPV4, Ipv4Literal.parse(literal));
    }

    private static AddressCategory classifyV6(String literal) {
        return AddressRangeClassifier.classify(AddressFamily.IPV6, Ipv6Literal.parse(literal));
    }
}
