package com.knowimran.rivoquery.platform.destination.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.application.destination.AddressFamily;
import org.junit.jupiter.api.Test;

class IpLiteralParsingTest {

    @Test
    void parsesStrictIpv4Literals() {
        assertThat(Ipv4Literal.parse("10.20.30.40")).isEqualTo(new byte[] {10, 20, 30, 40});
        assertThat(Ipv4Literal.parse("0.0.0.0")).isEqualTo(new byte[4]);
        assertThat(Ipv4Literal.parse("255.255.255.255"))
                .isEqualTo(new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 255});
    }

    @Test
    void rejectsAlternativeIpv4NotationAndLeadingZeros() {
        assertThat(Ipv4Literal.isStrict("010.0.0.1")).isFalse(); // leading zero / octal-shaped
        assertThat(Ipv4Literal.isStrict("0x0a.0.0.1")).isFalse(); // hex-shaped
        assertThat(Ipv4Literal.isStrict("10.0.1")).isFalse(); // short form
        assertThat(Ipv4Literal.isStrict("256.0.0.1")).isFalse(); // out of range
        assertThat(Ipv4Literal.isStrict("1.2.3.4.5")).isFalse();
        assertThatThrownBy(() -> Ipv4Literal.parse("010.0.0.1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesStrictIpv6LiteralsIncludingCompressionAndEmbeddedIpv4() {
        assertThat(Ipv6Literal.parse("::1"))
                .isEqualTo(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
        assertThat(Ipv6Literal.parse("::")).isEqualTo(new byte[16]);
        assertThat(Ipv6Literal.parse("2001:db8::1"))
                .isEqualTo(new byte[] {0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
        assertThat(Ipv6Literal.parse("::ffff:192.168.1.1"))
                .isEqualTo(new byte[] {
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, (byte) 192, (byte) 168, 1, 1
                });
        assertThat(Ipv6Literal.parse("1:2:3:4:5:6:7:8"))
                .isEqualTo(new byte[] {0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 8});
    }

    @Test
    void rejectsZoneIdsBracketsAndStructurallyInvalidIpv6() {
        assertThat(Ipv6Literal.isStrict("fe80::1%eth0")).isFalse();
        assertThat(Ipv6Literal.isStrict("[::1]")).isFalse();
        assertThat(Ipv6Literal.isStrict("1:2:3:4:5:6:7:8:9")).isFalse(); // too many groups
        assertThat(Ipv6Literal.isStrict("1:2:3")).isFalse(); // too few, uncompressed
        assertThat(Ipv6Literal.isStrict("::1::2")).isFalse(); // two compressions
        assertThat(Ipv6Literal.isStrict("gggg::1")).isFalse(); // non-hex
        assertThatThrownBy(() -> Ipv6Literal.parse("::1::2")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Regression for a prior review finding: {@code Character.isDigit}/{@code Character.digit} are
     * Unicode-aware and would accept e.g. fullwidth or Arabic-Indic digit variants, silently
     * classifying a hostname-shaped non-ASCII string as an IP literal while a downstream component
     * (e.g. TLS SNI) would dial the original, differently-interpreted non-ASCII string.
     */
    @Test
    void rejectsNonAsciiDigitVariantsRatherThanNormalizingAndAccepting() {
        String fullwidthLoopback = "１２７.0.0.1"; // fullwidth "127".0.0.1
        String arabicIndicPublic = "٩٣.184.216.34"; // Arabic-Indic "93".184.216.34
        assertThat(Ipv4Literal.isStrict(fullwidthLoopback)).isFalse();
        assertThat(Ipv4Literal.isStrict(arabicIndicPublic)).isFalse();
        assertThatThrownBy(() -> Ipv4Literal.parse(fullwidthLoopback)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ipv4Literal.parse(arabicIndicPublic)).isInstanceOf(IllegalArgumentException.class);

        assertThat(Ipv6Literal.isStrict("::" + "１")).isFalse(); // fullwidth "1" hex group
        assertThat(Ipv6Literal.isStrict("::ffff:" + fullwidthLoopback)).isFalse();
        assertThatThrownBy(() -> Ipv6Literal.parse("::" + "１")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cidrRangeMatchesFamilyAndPrefix() {
        CidrRange privateBlock = CidrRange.parse("10.0.0.0/8");
        assertThat(privateBlock.contains(AddressFamily.IPV4, new byte[] {10, 1, 2, 3})).isTrue();
        assertThat(privateBlock.contains(AddressFamily.IPV4, new byte[] {11, 1, 2, 3})).isFalse();
        assertThat(privateBlock.contains(AddressFamily.IPV6, new byte[16])).isFalse();

        CidrRange uniqueLocal = CidrRange.parse("fc00::/7");
        assertThat(uniqueLocal.contains(AddressFamily.IPV6, Ipv6Literal.parse("fd00::1"))).isTrue();
        assertThat(uniqueLocal.contains(AddressFamily.IPV6, Ipv6Literal.parse("fe00::1"))).isFalse();
    }
}
