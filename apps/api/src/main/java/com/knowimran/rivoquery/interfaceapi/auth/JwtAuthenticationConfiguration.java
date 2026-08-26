package com.knowimran.rivoquery.interfaceapi.auth;

import java.time.Clock;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link RequestAuthenticator}. Fails application startup (not a
 * request-time 500) if {@code rivoquery.auth.jwt.*} is unset or malformed -
 * matching this codebase's established fail-closed startup pattern (see
 * {@code MigrationOwnerSeparationValidator}) rather than silently accepting
 * unauthenticated traffic.
 */
@Configuration
@EnableConfigurationProperties(JwtAuthenticationProperties.class)
public class JwtAuthenticationConfiguration {

    @Bean
    RequestAuthenticator requestAuthenticator(JwtAuthenticationProperties properties) {
        if (properties.issuer() == null || properties.issuer().isBlank()) {
            throw new IllegalStateException("rivoquery.auth.jwt.issuer must be set");
        }
        if (properties.audience() == null || properties.audience().isBlank()) {
            throw new IllegalStateException("rivoquery.auth.jwt.audience must be set");
        }
        if (properties.hmacSecretBase64() == null || properties.hmacSecretBase64().isBlank()) {
            throw new IllegalStateException("rivoquery.auth.jwt.hmac-secret-base64 must be set");
        }
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(properties.hmacSecretBase64());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("rivoquery.auth.jwt.hmac-secret-base64 must be valid base64", notBase64);
        }
        if (secret.length < 32) {
            throw new IllegalStateException("rivoquery.auth.jwt.hmac-secret-base64 must decode to at least 32 bytes");
        }
        return new JwtBearerAuthenticator(properties.issuer(), properties.audience(), secret, Clock.systemUTC());
    }
}
