package com.knowimran.rivoquery.connection.application.destination;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Trusted destination-adapter output; this is data, not an authorization capability. */
public record DestinationAssessment(
        String canonicalHostname,
        String originalTlsHostname,
        int port,
        Set<VettedAddress> vettedAddresses,
        String addressSetFingerprint,
        String policyVersion,
        Instant resolvedAt,
        Instant expiresAt) {
    public DestinationAssessment {
        canonicalHostname = requireCanonicalName(canonicalHostname);
        originalTlsHostname = requireName(originalTlsHostname);
        vettedAddresses = Set.copyOf(Objects.requireNonNull(vettedAddresses, "vettedAddresses"));
        addressSetFingerprint = requireToken(addressSetFingerprint, "addressSetFingerprint");
        policyVersion = requireToken(policyVersion, "policyVersion");
        Objects.requireNonNull(resolvedAt, "resolvedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (port < 1 || port > 65_535 || vettedAddresses.isEmpty() || !resolvedAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("destination assessment is invalid");
        }
    }

    private static String requireCanonicalName(String value) {
        value = requireName(value);
        if (!value.equals(value.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("canonical hostname must be lowercase");
        }
        return value;
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("hostname is invalid");
        }
        return value;
    }

    private static String requireToken(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    @Override
    public String toString() {
        return "DestinationAssessment[REDACTED]";
    }
}
