package com.knowimran.rivoquery.interfaceapi.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hand-rolled HS256 bearer-JWT verifier (see {@link JwtAuthenticationProperties}
 * for why no external JWT library is used, and the HS256-only scope). Verifies
 * signature, {@code iss}, {@code aud}, and {@code exp}/{@code nbf} (with a
 * small clock-skew allowance) before returning the caller's identity. Every
 * failure - missing header, duplicate header, malformed token, bad signature,
 * wrong issuer/audience, expired/not-yet-valid token - throws the same
 * {@link AuthenticationFailedException}, never a detail that could help an
 * attacker distinguish failure reasons.
 */
public final class JwtBearerAuthenticator implements RequestAuthenticator {
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String issuer;
    private final String audience;
    private final byte[] hmacSecret;
    private final Clock clock;

    public JwtBearerAuthenticator(String issuer, String audience, byte[] hmacSecret, Clock clock) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.audience = Objects.requireNonNull(audience, "audience");
        this.hmacSecret = Objects.requireNonNull(hmacSecret, "hmacSecret").clone();
        this.clock = Objects.requireNonNull(clock, "clock");
        if (issuer.isBlank() || audience.isBlank() || this.hmacSecret.length < 32) {
            throw new IllegalArgumentException("jwt authenticator is misconfigured");
        }
    }

    @Override
    public AuthenticatedPrincipal authenticate(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        String token = extractExactBearerToken(request);
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3 || segments[0].isEmpty() || segments[1].isEmpty() || segments[2].isEmpty()) {
            throw new AuthenticationFailedException();
        }
        verifySignature(segments[0], segments[1], segments[2]);
        JsonNode header = parseJson(segments[0]);
        if (!"HS256".equals(header.path("alg").asText(null))) {
            throw new AuthenticationFailedException();
        }
        JsonNode claims = parseJson(segments[1]);
        requireIssuerMatches(claims);
        requireAudienceMatches(claims);
        requireTimeValid(claims);
        String subject = claims.path("sub").asText(null);
        if (subject == null || subject.isBlank()) {
            throw new AuthenticationFailedException();
        }
        try {
            return new AuthenticatedPrincipal(issuer, subject);
        } catch (IllegalArgumentException invalidSubject) {
            throw new AuthenticationFailedException();
        }
    }

    private String extractExactBearerToken(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(AUTHORIZATION_HEADER));
        if (values.size() != 1) {
            throw new AuthenticationFailedException();
        }
        String value = values.get(0);
        int spaceIndex = value.indexOf(' ');
        if (spaceIndex <= 0 || !"bearer".equalsIgnoreCase(value.substring(0, spaceIndex))) {
            throw new AuthenticationFailedException();
        }
        String token = value.substring(spaceIndex + 1).trim();
        if (token.isEmpty()) {
            throw new AuthenticationFailedException();
        }
        return token;
    }

    private void verifySignature(String headerSegment, String payloadSegment, String signatureSegment) {
        byte[] signingInput = (headerSegment + "." + payloadSegment).getBytes(StandardCharsets.US_ASCII);
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret, "HmacSHA256"));
            expected = mac.doFinal(signingInput);
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new AuthenticationFailedException();
        }
        byte[] actual;
        try {
            actual = base64UrlDecode(signatureSegment);
        } catch (IllegalArgumentException malformed) {
            throw new AuthenticationFailedException();
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new AuthenticationFailedException();
        }
    }

    private JsonNode parseJson(String segment) {
        try {
            return MAPPER.readTree(base64UrlDecode(segment));
        } catch (Exception malformed) {
            throw new AuthenticationFailedException();
        }
    }

    private void requireIssuerMatches(JsonNode claims) {
        String tokenIssuer = claims.path("iss").asText(null);
        if (tokenIssuer == null || !issuer.equals(tokenIssuer)) {
            throw new AuthenticationFailedException();
        }
    }

    private void requireAudienceMatches(JsonNode claims) {
        JsonNode aud = claims.path("aud");
        boolean matches;
        if (aud.isArray()) {
            matches = false;
            for (JsonNode entry : aud) {
                if (audience.equals(entry.asText(null))) {
                    matches = true;
                    break;
                }
            }
        } else {
            matches = audience.equals(aud.asText(null));
        }
        if (!matches) {
            throw new AuthenticationFailedException();
        }
    }

    private void requireTimeValid(JsonNode claims) {
        if (!claims.hasNonNull("exp") || !claims.path("exp").isNumber()) {
            throw new AuthenticationFailedException();
        }
        long now = clock.instant().getEpochSecond();
        long expiresAt = claims.path("exp").asLong();
        if (now - CLOCK_SKEW.toSeconds() >= expiresAt) {
            throw new AuthenticationFailedException();
        }
        if (claims.hasNonNull("nbf") && claims.path("nbf").isNumber()) {
            long notBefore = claims.path("nbf").asLong();
            if (now + CLOCK_SKEW.toSeconds() < notBefore) {
                throw new AuthenticationFailedException();
            }
        }
    }

    private static byte[] base64UrlDecode(String segment) {
        String padded = switch (segment.length() % 4) {
            case 2 -> segment + "==";
            case 3 -> segment + "=";
            default -> segment;
        };
        return Base64.getUrlDecoder().decode(padded);
    }
}
