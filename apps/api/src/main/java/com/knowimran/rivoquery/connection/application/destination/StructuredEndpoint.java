package com.knowimran.rivoquery.connection.application.destination;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A structured, pre-parsed database endpoint. Only a host (RFC 1123 hostname or a strict,
 * unbracketed IPv4/IPv6 literal), a port, and a database name matching a conservative
 * unquoted-identifier grammar are accepted. This deliberately rejects JDBC URL metacharacters
 * (e.g. {@code ?}, {@code &}, {@code /}, {@code ;}, whitespace, {@code @}, quotes) so a client can
 * never smuggle a URL fragment, driver property, socket factory, or path traversal sequence
 * through host/database fields toward a JDBC driver.
 */
public record StructuredEndpoint(String host, int port, String databaseName) {

    private static final int MAX_HOSTNAME_LENGTH = 253;
    private static final int MAX_LABEL_LENGTH = 63;
    private static final int MAX_IPV6_GROUPS = 8;

    private static final Pattern DATABASE_NAME =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");

    public StructuredEndpoint {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(databaseName, "databaseName");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("endpoint is invalid");
        }
        if (!isValidHost(host)) {
            throw new IllegalArgumentException("host is not a valid hostname or IP literal");
        }
        if (!DATABASE_NAME.matcher(databaseName).matches()) {
            throw new IllegalArgumentException("database name is not a valid identifier");
        }
    }

    private static boolean isValidHost(String host) {
        if (isIpv4Literal(host) || isIpv6Literal(host)) {
            return true;
        }
        // A dotted-quad-shaped value that is not a strict IPv4 literal (e.g. a leading zero, which
        // some libraries misinterpret as octal) is rejected outright rather than accepted as an
        // all-numeric hostname: different components downstream could disagree on how to interpret
        // it, which is exactly the alternative-numeric-notation ambiguity ADR-0013 forbids.
        if (looksLikeIpv4ButIsNotStrict(host)) {
            return false;
        }
        return isValidHostname(host);
    }

    private static boolean looksLikeIpv4ButIsNotStrict(String host) {
        String[] labels = host.split("\\.", -1);
        if (labels.length != 4) {
            return false;
        }
        for (String label : labels) {
            // Deliberately ASCII-only (not Character::isDigit, which is Unicode-aware): otherwise a
            // fullwidth- or Arabic-Indic-digit rendering of a dotted-quad would fall through this
            // check and be accepted as an ordinary hostname, while a downstream component (e.g. TLS
            // SNI) would dial the original non-ASCII string instead of the address it was vetted as.
            if (label.isEmpty() || !allAsciiDigits(label)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidHostname(String host) {
        if (host.isEmpty()
                || host.length() > MAX_HOSTNAME_LENGTH
                || host.startsWith(".")
                || host.endsWith(".")
                || host.contains("..")) {
            return false;
        }
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                boolean alphanumeric = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
                if (!alphanumeric && c != '-') {
                    return false;
                }
            }
            if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv4Literal(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (!isStrictDecimalOctet(octet)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStrictDecimalOctet(String octet) {
        if (octet.isEmpty() || octet.length() > 3 || !allAsciiDigits(octet)) {
            return false;
        }
        if (octet.length() > 1 && octet.charAt(0) == '0') {
            return false;
        }
        int value = 0;
        for (int i = 0; i < octet.length(); i++) {
            value = value * 10 + (octet.charAt(i) - '0');
        }
        return value <= 255;
    }

    /**
     * Deliberately ASCII-only (not {@link Character#isDigit(char)}, which is Unicode-aware and
     * would accept e.g. fullwidth or Arabic-Indic digit variants): see {@link
     * #looksLikeIpv4ButIsNotStrict(String)}.
     */
    private static boolean allAsciiDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Strict, bracket-free, zone-identifier-free IPv6 grammar check (grammar only; authoritative
     * byte-level parsing for policy purposes happens in the destination-resolution layer).
     */
    private static boolean isIpv6Literal(String value) {
        if (value.isEmpty() || value.indexOf('%') >= 0 || value.indexOf('[') >= 0 || value.indexOf(']') >= 0) {
            return false;
        }
        int firstCompression = value.indexOf("::");
        boolean compressed = firstCompression >= 0;
        if (compressed && value.indexOf("::", firstCompression + 1) >= 0) {
            return false;
        }
        String head = compressed ? value.substring(0, firstCompression) : value;
        String tail = compressed ? value.substring(firstCompression + 2) : "";
        if (!compressed && head.isEmpty()) {
            return false;
        }

        var headGroups = new java.util.ArrayList<>(splitGroups(head));
        var tailGroups = new java.util.ArrayList<>(splitGroups(tail));

        int ipv4GroupBonus = 0;
        if (!tailGroups.isEmpty() && tailGroups.get(tailGroups.size() - 1).indexOf('.') >= 0) {
            String candidate = tailGroups.remove(tailGroups.size() - 1);
            if (!isIpv4Literal(candidate)) {
                return false;
            }
            ipv4GroupBonus = 2;
        } else if (!compressed
                && !headGroups.isEmpty()
                && headGroups.get(headGroups.size() - 1).indexOf('.') >= 0) {
            String candidate = headGroups.remove(headGroups.size() - 1);
            if (!isIpv4Literal(candidate)) {
                return false;
            }
            ipv4GroupBonus = 2;
        }

        for (String group : headGroups) {
            if (!isHexGroup(group)) {
                return false;
            }
        }
        for (String group : tailGroups) {
            if (!isHexGroup(group)) {
                return false;
            }
        }

        int total = headGroups.size() + tailGroups.size() + ipv4GroupBonus;
        return compressed ? total < MAX_IPV6_GROUPS : total == MAX_IPV6_GROUPS;
    }

    private static java.util.List<String> splitGroups(String part) {
        if (part.isEmpty()) {
            return java.util.List.of();
        }
        return java.util.List.of(part.split(":", -1));
    }

    private static boolean isHexGroup(String group) {
        if (group.isEmpty() || group.length() > 4) {
            return false;
        }
        for (int i = 0; i < group.length(); i++) {
            // Deliberately ASCII-only (not Character.digit, which is Unicode-aware): see
            // #looksLikeIpv4ButIsNotStrict.
            if (!isAsciiHexDigit(group.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
