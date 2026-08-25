package com.knowimran.rivoquery.platform.destination.policy;

public enum AddressCategory {
    /** Ordinary public address; permitted without any operator rule. */
    PUBLIC,
    /** RFC 1918 / CGNAT / IPv6 unique-local; denied unless an operator allowlist rule matches. */
    PRIVATE,
    /**
     * Unspecified, loopback, link-local, multicast, broadcast, documentation, benchmarking,
     * reserved/special-use, or cloud-metadata; never permitted, not overridable by any rule.
     */
    PROHIBITED
}
