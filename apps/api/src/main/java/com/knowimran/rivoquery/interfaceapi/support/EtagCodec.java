package com.knowimran.rivoquery.interfaceapi.support;

import com.knowimran.rivoquery.connection.ConnectionVersion;
import java.util.regex.Pattern;

/**
 * Converts between {@code ConnectionVersion} and the OpenAPI contract's
 * strong-ETag / {@code If-Match} string shape ({@code "^\"[A-Za-z0-9._:-]+\"$"}).
 * Generated bean validation already enforces the header's outer shape, but
 * the inner numeric value still needs its own parse/range check before it
 * can become a {@link ConnectionVersion}.
 */
public final class EtagCodec {
    private static final Pattern QUOTED = Pattern.compile("^\"([A-Za-z0-9._:-]+)\"$");

    private EtagCodec() {}

    public static String format(ConnectionVersion version) {
        return "\"" + version.value() + "\"";
    }

    public static ConnectionVersion parse(String ifMatch) {
        return new ConnectionVersion(parseLong(ifMatch));
    }

    /**
     * The same strong-ETag shape, for a bare {@code long} version rather
     * than a {@link ConnectionVersion} - used for the connection-permission
     * collection's own version (see {@code ConnectionPermissionRepository
     * #currentCollectionVersion}), which is not connection-scoped state and
     * has no {@link ConnectionVersion} wrapper of its own.
     */
    public static String format(long version) {
        return "\"" + version + "\"";
    }

    /** See {@link #format(long)}. */
    public static long parseLong(String ifMatch) {
        var matcher = QUOTED.matcher(ifMatch);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("If-Match is not a well-formed strong ETag");
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException notNumeric) {
            throw new IllegalArgumentException("If-Match does not encode a numeric version", notNumeric);
        }
    }
}
