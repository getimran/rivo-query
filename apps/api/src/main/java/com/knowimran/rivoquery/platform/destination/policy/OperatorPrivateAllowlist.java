package com.knowimran.rivoquery.platform.destination.policy;

import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.destination.VettedAddress;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Deployment-operator-authored (never tenant/request-authored) rules permitting specific private
 * destinations, read from external configuration (see {@code DestinationPolicyProperties}). Per
 * ADR-0013 both the canonical name/port and every resolved address must match the same applicable
 * rule; a mixed set where only some addresses match is denied, not partially permitted.
 */
public final class OperatorPrivateAllowlist {
    private record CompiledRule(PrivateDestinationRule rule, List<CidrRange> cidrs) {}

    private final List<CompiledRule> compiledRules;

    public OperatorPrivateAllowlist(List<PrivateDestinationRule> rules) {
        Objects.requireNonNull(rules, "rules");
        this.compiledRules = rules.stream()
                .map(rule -> new CompiledRule(rule, rule.cidrs().stream().map(CidrRange::parse).toList()))
                .toList();
    }

    /**
     * @return true only when a single rule matches the connector kind, canonical hostname, and
     *     port, and every one of {@code addresses} falls within that same rule's CIDR list.
     */
    public boolean permits(
            DatabaseKind connectorKind, String canonicalHostname, int port, Collection<VettedAddress> addresses) {
        Objects.requireNonNull(connectorKind, "connectorKind");
        Objects.requireNonNull(canonicalHostname, "canonicalHostname");
        Objects.requireNonNull(addresses, "addresses");
        if (addresses.isEmpty()) {
            return false;
        }
        for (CompiledRule compiled : compiledRules) {
            PrivateDestinationRule rule = compiled.rule();
            if (rule.connectorKind() != connectorKind
                    || !rule.matchesName(canonicalHostname)
                    || !rule.matchesPort(port)) {
                continue;
            }
            boolean everyAddressMatches = addresses.stream().allMatch(address -> compiled.cidrs().stream()
                    .anyMatch(range -> range.contains(address.family(), address.addressBytes())));
            if (everyAddressMatches) {
                return true;
            }
        }
        return false;
    }
}
