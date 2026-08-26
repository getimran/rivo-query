package com.knowimran.rivoquery.interfaceapi.auth;

import java.util.Objects;

/**
 * The validated (issuer, subject) pair from a bearer JWT, per ADR-0009. Never
 * carries a role, tenant, or permission claim - those are always re-derived
 * server-side from current membership data (see {@code ActorContext.Authority}).
 */
public record AuthenticatedPrincipal(String issuer, String subject) {
    public AuthenticatedPrincipal {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        if (issuer.isBlank() || !issuer.equals(issuer.strip())) {
            throw new IllegalArgumentException("issuer must be non-blank and unpadded");
        }
        if (subject.isBlank() || !subject.equals(subject.strip())) {
            throw new IllegalArgumentException("subject must be non-blank and unpadded");
        }
    }
}
