package com.knowimran.rivoquery.platform.destination;

import com.knowimran.rivoquery.connection.DatabaseKind;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * External, operator-controlled destination-policy configuration. Never hardcoded and never
 * derived from tenant/request input; see {@code docs/OPERATIONS.md} for deployment guidance and
 * ADR-0013 for the rules this configures.
 *
 * @param resolverAddress optional operator-approved DNS resolver IP/hostname; when unset, the
 *     JVM/OS-configured default resolver is used. Restriction to approved resolvers in production
 *     is additionally enforced at the infrastructure/egress layer per ADR-0013.
 */
@ConfigurationProperties(prefix = "rivoquery.destination")
public record DestinationPolicyProperties(
        @DefaultValue("2s") Duration dnsTimeout,
        @DefaultValue("8") int maxCnameChainDepth,
        @DefaultValue("60") long maxCapabilityTtlSeconds,
        @DefaultValue("5") long fallbackTtlSeconds,
        @DefaultValue("destination-policy-v1") String policyVersion,
        String resolverAddress,
        List<PrivateRule> privateAllowlist) {
    public DestinationPolicyProperties {
        Objects.requireNonNull(dnsTimeout, "dnsTimeout");
        Objects.requireNonNull(policyVersion, "policyVersion");
        privateAllowlist = privateAllowlist == null ? List.of() : List.copyOf(privateAllowlist);
        if (dnsTimeout.isZero() || dnsTimeout.isNegative()) {
            throw new IllegalArgumentException("rivoquery.destination.dns-timeout must be positive");
        }
        if (maxCnameChainDepth < 1) {
            throw new IllegalArgumentException("rivoquery.destination.max-cname-chain-depth must be positive");
        }
        if (fallbackTtlSeconds < 1 || maxCapabilityTtlSeconds < 1 || fallbackTtlSeconds > maxCapabilityTtlSeconds) {
            throw new IllegalArgumentException(
                    "rivoquery.destination.fallback-ttl-seconds must be positive and at most"
                            + " max-capability-ttl-seconds");
        }
        if (policyVersion.isBlank()) {
            throw new IllegalArgumentException("rivoquery.destination.policy-version must not be blank");
        }
    }

    /**
     * One operator-authored bounded private-destination rule; see ADR-0013. {@code hostname} is
     * either an exact canonical name or a suffix beginning with {@code .}.
     */
    public record PrivateRule(
            DatabaseKind connectorKind,
            String hostname,
            @DefaultValue("1") int minPort,
            @DefaultValue("65535") int maxPort,
            List<String> cidrs) {
        public PrivateRule {
            Objects.requireNonNull(connectorKind, "connectorKind");
            Objects.requireNonNull(hostname, "hostname");
            cidrs = cidrs == null ? List.of() : List.copyOf(cidrs);
            if (hostname.isBlank() || minPort < 1 || maxPort > 65_535 || minPort > maxPort || cidrs.isEmpty()) {
                throw new IllegalArgumentException("rivoquery.destination.private-allowlist rule is invalid");
            }
        }
    }
}
