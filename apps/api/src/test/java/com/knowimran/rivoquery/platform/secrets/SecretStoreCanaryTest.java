package com.knowimran.rivoquery.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretStoreCanaryTest {
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void probeReportsHealthyWhenTheKeyRingAndStorageDirectoryBothWork() {
        var keyRing = KeyEncryptionKeyRing.of(Map.of("v1", AesGcmCipher.randomBytes(32)), "v1");
        var canary = new SecretStoreCanary(directory, keyRing, CLOCK);

        var result = canary.probe();

        assertThat(result.healthy()).isTrue();
        assertThat(result.failure()).isEmpty();
        assertThat(result.observedAt()).isEqualTo(NOW);
    }

    @Test
    void probeLeavesNoSyntheticFilesBehindAfterAHealthyRun() throws Exception {
        var keyRing = KeyEncryptionKeyRing.of(Map.of("v1", AesGcmCipher.randomBytes(32)), "v1");
        var canary = new SecretStoreCanary(directory, keyRing, CLOCK);

        canary.probe();

        try (var files = Files.list(directory)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void probeReportsUnhealthyWithASafeCategoryWhenTheStorageDirectoryIsUnavailable() throws Exception {
        var keyRing = KeyEncryptionKeyRing.of(Map.of("v1", AesGcmCipher.randomBytes(32)), "v1");
        Path notADirectory = directory.resolve("this-is-a-file");
        Files.createFile(notADirectory);
        var canary = new SecretStoreCanary(notADirectory, keyRing, CLOCK);

        var result = canary.probe();

        assertThat(result.healthy()).isFalse();
        assertThat(result.failure()).contains(SecretFailureCategory.STORAGE_UNAVAILABLE);
    }

    @Test
    void healthyResultCannotBeConstructedWithAFailureCategory() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new SecretStoreCanary.CanaryResult(
                        true, java.util.Optional.of(SecretFailureCategory.STORAGE_UNAVAILABLE), NOW)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
