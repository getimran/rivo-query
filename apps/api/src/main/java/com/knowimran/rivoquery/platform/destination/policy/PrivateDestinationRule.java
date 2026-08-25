package com.knowimran.rivoquery.platform.destination.policy;

import com.knowimran.rivoquery.connection.DatabaseKind;
import java.util.List;
import java.util.Objects;

/**
 * An operator-authored (never tenant- or request-authored) rule permitting a specific private
 * destination. Per ADR-0013 both the canonical name/port and every resolved address must match
 * the same rule; a rule can never widen the non-overridable prohibited-address deny set.
 *
 * @param hostnameSuffixOrExactName either an exact lower-case canonical hostname, or a suffix
 *     beginning with {@code .} (e.g. {@code .internal.example.com}) matching any subdomain
 * @param minPort inclusive lower bound of the permitted port range
 * @param maxPort inclusive upper bound of the permitted port range
 * @param cidrs the destination CIDR blocks (IPv4 or IPv6) this rule permits
 */
public record PrivateDestinationRule(
        DatabaseKind connectorKind, String hostnameSuffixOrExactName, int minPort, int maxPort, List<String> cidrs) {
    public PrivateDestinationRule {
        Objects.requireNonNull(connectorKind, "connectorKind");
        Objects.requireNonNull(hostnameSuffixOrExactName, "hostnameSuffixOrExactName");
        cidrs = List.copyOf(Objects.requireNonNull(cidrs, "cidrs"));
        if (hostnameSuffixOrExactName.isBlank()
                || minPort < 1
                || maxPort > 65_535
                || minPort > maxPort
                || cidrs.isEmpty()) {
            throw new IllegalArgumentException("private destination rule is invalid");
        }
    }

    boolean matchesName(String canonicalHostname) {
        if (hostnameSuffixOrExactName.startsWith(".")) {
            return canonicalHostname.endsWith(hostnameSuffixOrExactName)
                    && canonicalHostname.length() > hostnameSuffixOrExactName.length();
        }
        return canonicalHostname.equals(hostnameSuffixOrExactName);
    }

    boolean matchesPort(int port) {
        return port >= minPort && port <= maxPort;
    }
}
