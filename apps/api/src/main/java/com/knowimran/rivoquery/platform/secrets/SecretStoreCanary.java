package com.knowimran.rivoquery.platform.secrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Lightweight, self-contained health probe for the local secret store's two
 * externally supplied dependencies: the key-encryption-key ring and the
 * storage directory. Deliberately does not go through {@code SecretStore},
 * {@code SecretOperations}, or any grant - a canary has no tenant/connection
 * to authorize against, and minting a real grant is exclusively
 * {@code SecretCapabilityAuthority}'s job. Every value this probe reads or
 * writes is synthetic and discarded; {@link CanaryResult} never carries
 * plaintext, ciphertext, key material, or a file path.
 */
public final class SecretStoreCanary {
    private final Path storageDirectory;
    private final KeyEncryptionKeyRing keyRing;
    private final Clock clock;

    public SecretStoreCanary(Path storageDirectory, KeyEncryptionKeyRing keyRing, Clock clock) {
        this.storageDirectory = Objects.requireNonNull(storageDirectory, "storageDirectory");
        this.keyRing = Objects.requireNonNull(keyRing, "keyRing");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CanaryResult probe() {
        try {
            probeKeyRing();
            probeStorageDirectory();
            return CanaryResult.healthy(clock.instant());
        } catch (SecretProviderException categorized) {
            return CanaryResult.unhealthy(categorized.category(), clock.instant());
        } catch (RuntimeException uncategorized) {
            return CanaryResult.unhealthy(SecretFailureCategory.STORAGE_UNAVAILABLE, clock.instant());
        }
    }

    private void probeKeyRing() {
        byte[] synthetic = AesGcmCipher.randomBytes(AesGcmCipher.KEY_LENGTH_BYTES);
        byte[] additionalData = "secret-store-canary".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] roundTripped = null;
        try {
            var wrapped = keyRing.wrap(synthetic, additionalData);
            roundTripped = keyRing.unwrap(wrapped.keyId(), wrapped.nonce(), wrapped.ciphertext(), additionalData);
            if (!Arrays.equals(synthetic, roundTripped)) {
                throw new SecretIntegrityException();
            }
        } finally {
            Arrays.fill(synthetic, (byte) 0);
            if (roundTripped != null) {
                Arrays.fill(roundTripped, (byte) 0);
            }
        }
    }

    private void probeStorageDirectory() {
        Path probe = storageDirectory.resolve(".canary-" + UUID.randomUUID());
        try {
            Files.writeString(probe, "canary", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.readString(probe);
        } catch (IOException e) {
            // Files.writeString/readString's own IOException message
            // routinely embeds the absolute probe/storage-directory path;
            // redact before it can be surfaced.
            throw SecretStorageUnavailableException.redacting(e);
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // best-effort cleanup of a throwaway synthetic probe file
            }
        }
    }

    /**
     * Safe, non-secret-shaped canary outcome: a health flag, an optional
     * closed-set failure category, and an observation timestamp only.
     */
    public record CanaryResult(boolean healthy, Optional<SecretFailureCategory> failure, Instant observedAt) {
        public CanaryResult {
            Objects.requireNonNull(failure, "failure");
            Objects.requireNonNull(observedAt, "observedAt");
            if (healthy != failure.isEmpty()) {
                throw new IllegalArgumentException("healthy canary result must not carry a failure category");
            }
        }

        static CanaryResult healthy(Instant observedAt) {
            return new CanaryResult(true, Optional.empty(), observedAt);
        }

        static CanaryResult unhealthy(SecretFailureCategory category, Instant observedAt) {
            return new CanaryResult(false, Optional.of(category), observedAt);
        }
    }
}
