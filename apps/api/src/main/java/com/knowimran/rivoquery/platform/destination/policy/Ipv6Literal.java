package com.knowimran.rivoquery.platform.destination.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * Strict IPv6 literal parser: no brackets, no zone identifiers, at most one {@code ::}
 * compression, and support for an embedded dotted-quad IPv4 tail (mapped/compatible transition
 * forms). Never accepts anything that would let a client smuggle a hostname-shaped or
 * metacharacter-bearing string through as if it were an address.
 */
final class Ipv6Literal {
    private static final int GROUP_COUNT = 8;

    private Ipv6Literal() {}

    static byte[] parse(String value) {
        if (value.isEmpty() || value.indexOf('%') >= 0 || value.indexOf('[') >= 0 || value.indexOf(']') >= 0) {
            throw new IllegalArgumentException("not a strict IPv6 literal");
        }
        int firstCompression = value.indexOf("::");
        boolean compressed = firstCompression >= 0;
        if (compressed && value.indexOf("::", firstCompression + 1) >= 0) {
            throw new IllegalArgumentException("not a strict IPv6 literal");
        }
        String head = compressed ? value.substring(0, firstCompression) : value;
        String tail = compressed ? value.substring(firstCompression + 2) : "";
        if (!compressed && head.isEmpty()) {
            throw new IllegalArgumentException("not a strict IPv6 literal");
        }

        List<String> headGroups = new ArrayList<>(splitGroups(head));
        List<String> tailGroups = new ArrayList<>(splitGroups(tail));

        byte[] embeddedIpv4 = null;
        if (!tailGroups.isEmpty() && tailGroups.get(tailGroups.size() - 1).indexOf('.') >= 0) {
            embeddedIpv4 = Ipv4Literal.parse(tailGroups.remove(tailGroups.size() - 1));
        } else if (!compressed
                && !headGroups.isEmpty()
                && headGroups.get(headGroups.size() - 1).indexOf('.') >= 0) {
            embeddedIpv4 = Ipv4Literal.parse(headGroups.remove(headGroups.size() - 1));
        }

        int ipv4GroupBonus = embeddedIpv4 == null ? 0 : 2;
        int explicitGroups = headGroups.size() + tailGroups.size() + ipv4GroupBonus;
        if (compressed ? explicitGroups >= GROUP_COUNT : explicitGroups != GROUP_COUNT) {
            throw new IllegalArgumentException("not a strict IPv6 literal");
        }

        byte[] result = new byte[16];
        int index = 0;
        for (String group : headGroups) {
            index = writeGroup(result, index, group);
        }
        if (compressed) {
            index += (GROUP_COUNT - explicitGroups) * 2;
        }
        for (String group : tailGroups) {
            index = writeGroup(result, index, group);
        }
        if (embeddedIpv4 != null) {
            System.arraycopy(embeddedIpv4, 0, result, 12, 4);
        }
        return result;
    }

    static boolean isStrict(String value) {
        try {
            parse(value);
            return true;
        } catch (IllegalArgumentException notStrict) {
            return false;
        }
    }

    private static List<String> splitGroups(String part) {
        if (part.isEmpty()) {
            return List.of();
        }
        return List.of(part.split(":", -1));
    }

    private static int writeGroup(byte[] result, int index, String group) {
        if (group.isEmpty() || group.length() > 4) {
            throw new IllegalArgumentException("not a strict IPv6 literal");
        }
        int value = 0;
        for (int i = 0; i < group.length(); i++) {
            int digit = asciiHexDigit(group.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("not a strict IPv6 literal");
            }
            value = (value << 4) | digit;
        }
        result[index] = (byte) (value >> 8);
        result[index + 1] = (byte) value;
        return index + 2;
    }

    /**
     * ASCII-only hex digit value, or -1 when not one; deliberately not {@link Character#digit(char,
     * int)}, which is Unicode-aware and would accept non-ASCII digit variants (e.g. fullwidth or
     * Arabic-Indic digits) that a downstream component (e.g. TLS SNI) would interpret differently
     * from how this literal was classified/vetted.
     */
    private static int asciiHexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
