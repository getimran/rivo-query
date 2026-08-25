package com.knowimran.rivoquery.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretEnvelopeCodecTest {
    @TempDir
    Path directory;

    private static final LocalSecretEnvelope ENVELOPE = new LocalSecretEnvelope(
            1,
            "opaque-id-0123456789ab",
            "v1",
            AesGcmCipher.randomBytes(12),
            AesGcmCipher.randomBytes(48),
            AesGcmCipher.randomBytes(12),
            "binding-fingerprint-01",
            "ciphertext-and-tag-bytes".getBytes(StandardCharsets.UTF_8));

    @Test
    void writeThenReadRoundTripsEveryFieldExactly() {
        Path file = directory.resolve("envelope.secret");
        SecretEnvelopeCodec.write(file, ENVELOPE);

        LocalSecretEnvelope read = SecretEnvelopeCodec.read(file);

        // LocalSecretEnvelope is a record with byte[] components, whose
        // generated equals() compares arrays by reference; recursive
        // comparison is required to assert the round trip preserved content.
        assertThat(read).usingRecursiveComparison().isEqualTo(ENVELOPE);
    }

    @Test
    void readingAFileWithACorruptMagicHeaderFailsClosed() {
        Path file = directory.resolve("bad-magic.secret");
        SecretEnvelopeCodec.write(file, ENVELOPE);
        corruptFirstBytes(file);

        assertThatThrownBy(() -> SecretEnvelopeCodec.read(file))
                .isInstanceOf(SecretIntegrityException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, ENVELOPE.opaqueId(), directory.toAbsolutePath().toString()));
    }

    @Test
    void readingATruncatedFileFailsClosed() throws Exception {
        Path file = directory.resolve("truncated.secret");
        SecretEnvelopeCodec.write(file, ENVELOPE);
        long fullLength = Files.size(file);
        try (var raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(fullLength / 2);
        }

        assertThatThrownBy(() -> SecretEnvelopeCodec.read(file))
                .isInstanceOf(SecretIntegrityException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, ENVELOPE.opaqueId(), directory.toAbsolutePath().toString()));
    }

    @Test
    void readingAFileWithTrailingGarbageFailsClosed() throws Exception {
        Path file = directory.resolve("trailing-garbage.secret");
        SecretEnvelopeCodec.write(file, ENVELOPE);
        Files.write(file, "extra-garbage-bytes".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(() -> SecretEnvelopeCodec.read(file))
                .isInstanceOf(SecretIntegrityException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, ENVELOPE.opaqueId(), directory.toAbsolutePath().toString()));
    }

    @Test
    void readingAMissingFilePropagatesAStorageFailure() {
        assertThatThrownBy(() -> SecretEnvelopeCodec.read(directory.resolve("missing.secret")))
                .isInstanceOf(SecretStorageUnavailableException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, directory.toAbsolutePath().toString()));
    }

    @Test
    void toStringIsRedacted() {
        assertThat(ENVELOPE.toString()).isEqualTo("LocalSecretEnvelope[REDACTED]");
    }

    private static void corruptFirstBytes(Path file) {
        try (var raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(0);
            raf.write(new byte[] {'X', 'X', 'X', 'X'});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
