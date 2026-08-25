package com.knowimran.rivoquery.platform.destination.policy;

import com.knowimran.rivoquery.connection.application.destination.AddressFamily;

/** A single-family CIDR block, parsed from a strict IP-literal network address plus prefix. */
final class CidrRange {
    private final AddressFamily family;
    private final byte[] network;
    private final int prefixLength;

    private CidrRange(AddressFamily family, byte[] network, int prefixLength) {
        this.family = family;
        this.network = network;
        this.prefixLength = prefixLength;
    }

    static CidrRange parse(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("cidr must include a prefix length");
        }
        String addressPart = cidr.substring(0, slash);
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException notNumeric) {
            throw new IllegalArgumentException("cidr prefix length is not numeric");
        }
        if (Ipv4Literal.isStrict(addressPart)) {
            if (prefixLength < 0 || prefixLength > 32) {
                throw new IllegalArgumentException("ipv4 cidr prefix length out of range");
            }
            return new CidrRange(AddressFamily.IPV4, Ipv4Literal.parse(addressPart), prefixLength);
        }
        if (Ipv6Literal.isStrict(addressPart)) {
            if (prefixLength < 0 || prefixLength > 128) {
                throw new IllegalArgumentException("ipv6 cidr prefix length out of range");
            }
            return new CidrRange(AddressFamily.IPV6, Ipv6Literal.parse(addressPart), prefixLength);
        }
        throw new IllegalArgumentException("cidr network address is not a strict IP literal");
    }

    boolean contains(AddressFamily addressFamily, byte[] address) {
        if (addressFamily != family) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (address[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }
}
