package com.knowimran.rivoquery.platform.secrets;

import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A separate externally supplied key used only to derive
 * {@link #opaqueId}, the create-only envelope identifier, from the mutation
 * operation ID and binding, so that an ambiguous retry locates the same
 * envelope without persisting either value. This key never encrypts secret
 * material and never derives anything from plaintext: a keyed digest over
 * plaintext material would let anyone holding the secret directory and this
 * key alone brute-force low-entropy target-database passwords offline,
 * defeating the deliberate separation between this key and the AES-GCM
 * key-encryption key. A create-only-race retry is instead verified by
 * decrypting the already-persisted envelope and comparing recovered
 * plaintext to the incoming plaintext (see {@link LocalSecretStorage}), never
 * by persisting a plaintext-derived fingerprint.
 *
 * <p>The derived opaque ID cannot be inverted back to the operation ID or
 * binding without this key.
 */
public final class SecretReferenceKey {
    static final String ENV_VAR = "RIVOQUERY_SECRETS_LOCAL_REFERENCE_KEY";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final byte[] keyBytes;

    private SecretReferenceKey(byte[] keyBytes) {
        this.keyBytes = keyBytes;
    }

    public static SecretReferenceKey of(byte[] keyBytes) {
        Objects.requireNonNull(keyBytes, "keyBytes");
        if (keyBytes.length < AesGcmCipher.KEY_LENGTH_BYTES) {
            throw new SecretKeyUnavailableException();
        }
        return new SecretReferenceKey(keyBytes.clone());
    }

    /**
     * Reads the {@code RIVOQUERY_SECRETS_LOCAL_REFERENCE_KEY} runtime
     * configuration value (Base64-encoded, at least 256 bits). Fails closed
     * on missing, blank, or malformed configuration.
     */
    public static SecretReferenceKey fromEnvironment(Function<String, String> lookup) {
        String raw = Objects.requireNonNull(lookup, "lookup").apply(ENV_VAR);
        if (raw == null || raw.isBlank()) {
            throw new SecretKeyUnavailableException();
        }
        try {
            return of(Base64.getDecoder().decode(raw.strip()));
        } catch (IllegalArgumentException notBase64) {
            throw new SecretKeyUnavailableException(notBase64);
        }
    }

    String opaqueId(SecretBinding binding, String mutationOperationId) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(mutationOperationId, "mutationOperationId");
        byte[] input = concat(
                "opaque-id:".getBytes(StandardCharsets.UTF_8),
                SecretBindingCodec.additionalData(1, binding),
                mutationOperationId.getBytes(StandardCharsets.UTF_8));
        return ENCODER.encodeToString(hmac(input));
    }

    private byte[] hmac(byte[] input) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALGORITHM));
            return mac.doFinal(input);
        } catch (java.security.GeneralSecurityException e) {
            throw new SecretKeyUnavailableException(e);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    @Override
    public String toString() {
        return "SecretReferenceKey[REDACTED]";
    }
}
