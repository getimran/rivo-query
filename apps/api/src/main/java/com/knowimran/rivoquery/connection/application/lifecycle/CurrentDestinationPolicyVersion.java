package com.knowimran.rivoquery.connection.application.lifecycle;

/**
 * Supplies the operator-configured destination-policy version tag current
 * "now" (e.g. {@code DestinationPolicyProperties.policyVersion()}), used for
 * {@code ValidatedDestination.requireValid}'s consumption-time re-check and
 * for constructing a {@code PoolKey} without requiring a fresh DNS lookup:
 * the policy version is a configuration tag, not a DNS-derived value.
 */
@FunctionalInterface
public interface CurrentDestinationPolicyVersion {
    String value();
}
