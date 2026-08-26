package com.knowimran.rivoquery.interfaceapi.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A canonical, lowercase-hex SHA-256 digest of an idempotent mutation's
 * meaningful inputs (path parameters plus request body), used to detect
 * whether an {@code Idempotency-Key} reuse is an exact replay or a genuine
 * conflict (OpenAPI: "Reuse with a different request returns 409").
 *
 * <p>Secret-bearing request fields (the OpenAPI {@code writeOnly} fields:
 * {@code password}, {@code caCertificatePem}, {@code clientCertificatePem},
 * {@code clientPrivateKeyPem}) are <em>excluded</em> from the digest before
 * it is hashed, so the {@code request_fingerprint} column never persists a
 * credential-derived artifact that could be recovered by offline dictionary
 * attack from control-plane read access (backup/replica/SQL-read). Secrets
 * do not participate in replay-vs-conflict identity: the non-secret shape
 * (which resource, what non-secret configuration) is what idempotency keys.
 */
public final class RequestFingerprint {

    /** OpenAPI {@code writeOnly} request fields that must never inform the stored digest. */
    private static final Set<String> REDACTED_FIELD_NAMES = Set.of(
            "password", "caCertificatePem", "clientCertificatePem", "clientPrivateKeyPem");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    private RequestFingerprint() {}

    public static String of(Object... parts) {
        String canonical = Arrays.stream(parts)
                .map(RequestFingerprint::render)
                .collect(Collectors.joining("|"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String render(Object part) {
        if (part == null) {
            return "";
        }
        if (part instanceof String string) {
            return string;
        }
        try {
            JsonNode canonical = MAPPER.valueToTree(part);
            redactSecrets(canonical);
            return MAPPER.writeValueAsString(canonical);
        } catch (Exception unserializable) {
            throw new IllegalArgumentException("request part could not be canonicalized", unserializable);
        }
    }

    private static void redactSecrets(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> toRemove = new ArrayList<>();
            object.fieldNames().forEachRemaining(name -> {
                if (REDACTED_FIELD_NAMES.contains(name)) {
                    toRemove.add(name);
                } else {
                    redactSecrets(object.get(name));
                }
            });
            toRemove.forEach(object::remove);
        } else if (node.isArray()) {
            node.forEach(RequestFingerprint::redactSecrets);
        }
    }
}
