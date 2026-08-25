package com.knowimran.rivoquery.platform.destination.policy;

/**
 * Strict IPv4 literal parser: exactly four dot-separated decimal octets 0-255, no leading zeros,
 * no alternative (hex/octal/short-form) numeric notation.
 */
final class Ipv4Literal {
    private Ipv4Literal() {}

    static byte[] parse(String text) {
        String[] octets = text.split("\\.", -1);
        if (octets.length != 4) {
            throw new IllegalArgumentException("not a strict IPv4 literal");
        }
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) {
            result[i] = (byte) parseStrictOctet(octets[i]);
        }
        return result;
    }

    static boolean isStrict(String text) {
        try {
            parse(text);
            return true;
        } catch (IllegalArgumentException notStrict) {
            return false;
        }
    }

    private static int parseStrictOctet(String octet) {
        if (octet.isEmpty() || octet.length() > 3) {
            throw new IllegalArgumentException("invalid ipv4 octet");
        }
        for (int i = 0; i < octet.length(); i++) {
            // Deliberately ASCII-only: Character.isDigit is Unicode-aware and would accept
            // non-ASCII digit variants (e.g. fullwidth or Arabic-Indic digits), which could then
            // classify/vet one string while a downstream component (e.g. TLS SNI) dials the
            // original, differently-interpreted non-ASCII string.
            if (!isAsciiDigit(octet.charAt(i))) {
                throw new IllegalArgumentException("invalid ipv4 octet");
            }
        }
        if (octet.length() > 1 && octet.charAt(0) == '0') {
            throw new IllegalArgumentException("invalid ipv4 octet leading zero");
        }
        int value = 0;
        for (int i = 0; i < octet.length(); i++) {
            value = value * 10 + (octet.charAt(i) - '0');
        }
        if (value > 255) {
            throw new IllegalArgumentException("invalid ipv4 octet range");
        }
        return value;
    }

    /** ASCII-only decimal digit check; deliberately not {@link Character#isDigit(char)}. */
    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
