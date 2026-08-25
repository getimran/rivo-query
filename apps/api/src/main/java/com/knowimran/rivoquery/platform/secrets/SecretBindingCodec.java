package com.knowimran.rivoquery.platform.secrets;

import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/**
 * Deterministic, non-secret encodings of a {@link SecretBinding}.
 *
 * <p>{@link #additionalData} is used as AES-GCM authenticated additional
 * data for the material ciphertext, so that moving ciphertext to a different
 * tenant/connection/purpose/version - or truncating/mutating that tuple -
 * fails authentication rather than silently decrypting. It is reconstructed
 * identically at stage time (from the caller-supplied binding) and at
 * resolve/revoke time (from the authorized grant's binding); it is never
 * persisted.
 *
 * <p>{@link #fingerprint} is the non-sensitive binding fingerprint carried
 * on {@code SecretReference} and in the envelope header, used as a fast,
 * pre-decryption cross-check that a reference/grant still agrees with the
 * envelope it names.
 */
final class SecretBindingCodec {
    private SecretBindingCodec() {
    }

    static byte[] additionalData(int formatVersion, SecretBinding binding) {
        Objects.requireNonNull(binding, "binding");
        var buffer = new ByteArrayOutputStream();
        writeSegment(buffer, Integer.toString(formatVersion));
        writeSegment(buffer, binding.workspaceId().value());
        writeSegment(buffer, binding.connectionId().value());
        writeSegment(buffer, binding.purpose().name());
        writeSegment(buffer, Long.toString(binding.version()));
        return buffer.toByteArray();
    }

    static String fingerprint(SecretBinding binding) {
        byte[] canonical = additionalData(1, binding);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to be available", e);
        }
    }

    private static void writeSegment(ByteArrayOutputStream buffer, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        buffer.write(bytes.length >> 24);
        buffer.write(bytes.length >> 16);
        buffer.write(bytes.length >> 8);
        buffer.write(bytes.length);
        buffer.write(bytes, 0, bytes.length);
    }
}
