package com.knowimran.rivoquery.platform.connector;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Defense-in-depth structural validation for the fields that feed a trusted JDBC URL.
 *
 * <p>{@code StructuredEndpoint} performs only minimal (non-blank, canonical-whitespace)
 * validation at the domain layer. This class enforces a strict character allowlist
 * independently, at the point the URL string is assembled, so that a host or database name
 * containing URL/JDBC metacharacters (e.g. {@code &}, {@code ?}, {@code #}, {@code /}, control
 * characters, or whitespace) can never alter the meaning of the server-built JDBC URL or inject
 * an additional driver property — regardless of whether upstream validation is complete.
 */
final class TrustedEndpointValidation {

    private static final int MAX_HOST_LENGTH = 255;
    private static final int MAX_DATABASE_NAME_LENGTH = 63;

    /** RFC 1123 hostname label grammar; also matches dotted-decimal IPv4 literals. */
    private static final Pattern HOSTNAME_OR_IPV4 = Pattern.compile(
            "^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");

    /**
     * Bare (unbracketed) IPv6 literal character set, matching how {@code StructuredEndpoint}
     * represents an IPv6 host (no brackets, no zone identifier). This only constrains which
     * characters may appear (hex digits, colons, and dots for an embedded IPv4-mapped tail); it
     * deliberately does not re-implement full IPv6 grammar validation, which is StructuredEndpoint's
     * and the destination-resolution policy's responsibility.
     */
    private static final Pattern BARE_IPV6_CHARSET = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    /** Conservative, closed allowlist for a database/schema name used as a URL path segment. */
    private static final Pattern DATABASE_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,63}$");

    private TrustedEndpointValidation() {}

    static String requireTrustedHost(String host) {
        Objects.requireNonNull(host, "host");
        if (host.isEmpty() || host.length() > MAX_HOST_LENGTH) {
            throw new InvalidConnectorEndpointException("host");
        }
        boolean looksLikeIpv6 = host.indexOf(':') >= 0;
        boolean valid = looksLikeIpv6
                ? BARE_IPV6_CHARSET.matcher(host).matches()
                : HOSTNAME_OR_IPV4.matcher(host).matches();
        if (!valid) {
            throw new InvalidConnectorEndpointException("host");
        }
        return host;
    }

    /** JDBC/URL authority component for a trusted host: IPv6 literals must be bracketed. */
    static String toUrlHostComponent(String trustedHost) {
        return trustedHost.indexOf(':') >= 0 ? "[" + trustedHost + "]" : trustedHost;
    }

    static int requireTrustedPort(int port) {
        if (port < 1 || port > 65_535) {
            throw new InvalidConnectorEndpointException("port");
        }
        return port;
    }

    static String requireTrustedDatabaseName(String databaseName) {
        Objects.requireNonNull(databaseName, "databaseName");
        if (databaseName.length() > MAX_DATABASE_NAME_LENGTH
                || !DATABASE_NAME.matcher(databaseName).matches()) {
            throw new InvalidConnectorEndpointException("databaseName");
        }
        return databaseName;
    }
}
