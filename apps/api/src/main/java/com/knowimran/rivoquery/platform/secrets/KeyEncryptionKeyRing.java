package com.knowimran.rivoquery.platform.secrets;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A rotation set of externally supplied AES-256 key-encryption keys, plus
 * the identifier of the key new data-encryption keys are wrapped with. Key
 * bytes come only from runtime configuration (environment variables by
 * default); none are hardcoded or derived from anything checked into the
 * repository. Never logs or exposes key bytes; {@link #toString()} is
 * redacted.
 */
public final class KeyEncryptionKeyRing {
    static final String KEYS_ENV_VAR = "RIVOQUERY_SECRETS_LOCAL_KEYS";
    static final String CURRENT_KEY_ID_ENV_VAR = "RIVOQUERY_SECRETS_LOCAL_CURRENT_KEY_ID";

    private static final Pattern KEY_ID = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final Pattern ENTRY = Pattern.compile("^([a-zA-Z0-9_-]{1,64}):([A-Za-z0-9+/=_-]+)$");

    private final Map<String, byte[]> keysById;
    private final String currentKeyId;

    private KeyEncryptionKeyRing(Map<String, byte[]> keysById, String currentKeyId) {
        this.keysById = keysById;
        this.currentKeyId = currentKeyId;
    }

    /**
     * Builds a ring from an already-parsed key map. Every key must be
     * exactly 256 bits after Base64 decoding; {@code currentKeyId} must be
     * present in {@code keysById}.
     */
    public static KeyEncryptionKeyRing of(Map<String, byte[]> keysById, String currentKeyId) {
        Objects.requireNonNull(keysById, "keysById");
        Objects.requireNonNull(currentKeyId, "currentKeyId");
        if (keysById.isEmpty()) {
            throw new SecretKeyUnavailableException();
        }
        var copy = new LinkedHashMap<String, byte[]>();
        keysById.forEach((keyId, keyBytes) -> {
            if (!KEY_ID.matcher(keyId).matches()) {
                throw new SecretKeyUnavailableException();
            }
            if (keyBytes == null || keyBytes.length != AesGcmCipher.KEY_LENGTH_BYTES) {
                throw new SecretKeyUnavailableException();
            }
            copy.put(keyId, keyBytes.clone());
        });
        if (!copy.containsKey(currentKeyId)) {
            throw new SecretKeyUnavailableException();
        }
        return new KeyEncryptionKeyRing(Map.copyOf(copy), currentKeyId);
    }

    /**
     * Parses the {@code RIVOQUERY_SECRETS_LOCAL_KEYS} (comma-separated
     * {@code keyId:base64key} entries) and
     * {@code RIVOQUERY_SECRETS_LOCAL_CURRENT_KEY_ID} runtime configuration
     * values. {@code lookup} is typically {@code System.getenv()::get}; a
     * test double may supply synthetic values without touching real
     * environment variables. Fails closed (throws) on missing, blank, or
     * malformed configuration - this adapter never derives a key from
     * anything checked into the repository.
     */
    public static KeyEncryptionKeyRing fromEnvironment(Function<String, String> lookup) {
        Objects.requireNonNull(lookup, "lookup");
        String rawKeys = lookup.apply(KEYS_ENV_VAR);
        String currentKeyId = lookup.apply(CURRENT_KEY_ID_ENV_VAR);
        if (rawKeys == null || rawKeys.isBlank() || currentKeyId == null || currentKeyId.isBlank()) {
            throw new SecretKeyUnavailableException();
        }
        var keysById = new LinkedHashMap<String, byte[]>();
        for (String entry : rawKeys.split(",")) {
            String trimmed = entry.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            var matcher = ENTRY.matcher(trimmed);
            if (!matcher.matches()) {
                throw new SecretKeyUnavailableException();
            }
            byte[] keyBytes;
            try {
                keyBytes = Base64.getDecoder().decode(matcher.group(2));
            } catch (IllegalArgumentException notBase64) {
                throw new SecretKeyUnavailableException(notBase64);
            }
            keysById.put(matcher.group(1), keyBytes);
        }
        return of(keysById, currentKeyId.strip());
    }

    WrappedDataKey wrap(byte[] dataEncryptionKey, byte[] additionalData) {
        byte[] nonce = AesGcmCipher.randomBytes(AesGcmCipher.NONCE_LENGTH_BYTES);
        byte[] ciphertext = AesGcmCipher.encrypt(keysById.get(currentKeyId), nonce, dataEncryptionKey, additionalData);
        return new WrappedDataKey(currentKeyId, nonce, ciphertext);
    }

    byte[] unwrap(String keyId, byte[] nonce, byte[] wrappedDataKey, byte[] additionalData) {
        byte[] keyBytes = keysById.get(Objects.requireNonNull(keyId, "keyId"));
        if (keyBytes == null) {
            throw new SecretKeyUnavailableException();
        }
        return AesGcmCipher.decrypt(keyBytes, nonce, wrappedDataKey, additionalData);
    }

    @Override
    public String toString() {
        return "KeyEncryptionKeyRing[REDACTED]";
    }

    record WrappedDataKey(String keyId, byte[] nonce, byte[] ciphertext) {
        @Override
        public String toString() {
            return "WrappedDataKey[REDACTED]";
        }
    }
}
