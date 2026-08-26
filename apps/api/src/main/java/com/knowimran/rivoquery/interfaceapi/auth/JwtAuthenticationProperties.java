package com.knowimran.rivoquery.interfaceapi.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External configuration for {@link JwtBearerAuthenticator}. All three
 * fields are required at startup; see {@link JwtAuthenticationConfiguration}
 * for the fail-closed check.
 *
 * <p>Scope note (see docs/IMPLEMENTATION_TASKS.md P2-130 evidence): only
 * HS256 (shared-secret) signature verification is implemented. A typical
 * production OIDC provider issues RS256 tokens validated against a JWKS
 * endpoint; wiring that is flagged as necessary follow-up work, not invented
 * here without a reviewed design (matches this codebase's established
 * "documented local adapter now, reviewed production port later" pattern -
 * see {@code platform.secrets.UnimplementedManagedSecretProvider}).
 */
@ConfigurationProperties(prefix = "rivoquery.auth.jwt")
public record JwtAuthenticationProperties(String issuer, String audience, String hmacSecretBase64) {
}
