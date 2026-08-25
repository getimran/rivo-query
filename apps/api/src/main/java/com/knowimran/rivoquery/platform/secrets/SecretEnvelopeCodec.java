package com.knowimran.rivoquery.platform.secrets;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fixed binary serialization for {@link LocalSecretEnvelope}. Any structural
 * or magic-header mismatch, truncation, or trailing garbage is treated as
 * corruption and fails closed via {@link SecretIntegrityException} - it is
 * never partially trusted.
 */
final class SecretEnvelopeCodec {
    private static final byte[] MAGIC = {'R', 'V', 'Q', '1'};
    private static final int MAX_FIELD_LENGTH = 1 << 20;

    private SecretEnvelopeCodec() {
    }

    static void write(Path file, LocalSecretEnvelope envelope) {
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.write(MAGIC);
            out.writeInt(envelope.formatVersion());
            writeString(out, envelope.opaqueId());
            writeString(out, envelope.keyId());
            writeBytes(out, envelope.wrapNonce());
            writeBytes(out, envelope.wrappedDataKey());
            writeBytes(out, envelope.materialNonce());
            writeString(out, envelope.bindingFingerprint());
            writeBytes(out, envelope.ciphertext());
            out.flush();
        } catch (IOException e) {
            throw SecretStorageUnavailableException.redacting(e);
        }
    }

    static LocalSecretEnvelope read(Path file) {
        try (var in = new DataInputStream(Files.newInputStream(file))) {
            byte[] magic = in.readNBytes(MAGIC.length);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new SecretIntegrityException();
            }
            int formatVersion = in.readInt();
            String opaqueId = readString(in);
            String keyId = readString(in);
            byte[] wrapNonce = readBytes(in);
            byte[] wrappedDataKey = readBytes(in);
            byte[] materialNonce = readBytes(in);
            String bindingFingerprint = readString(in);
            byte[] ciphertext = readBytes(in);
            if (in.read() != -1) {
                throw new SecretIntegrityException();
            }
            return new LocalSecretEnvelope(
                    formatVersion,
                    opaqueId,
                    keyId,
                    wrapNonce,
                    wrappedDataKey,
                    materialNonce,
                    bindingFingerprint,
                    ciphertext);
        } catch (EOFException | java.io.UTFDataFormatException truncated) {
            throw new SecretIntegrityException(truncated);
        } catch (IOException e) {
            throw SecretStorageUnavailableException.redacting(e);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        out.writeUTF(value);
    }

    private static String readString(DataInputStream in) throws IOException {
        return in.readUTF();
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_FIELD_LENGTH) {
            throw new SecretIntegrityException();
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new SecretIntegrityException();
        }
        return bytes;
    }
}
