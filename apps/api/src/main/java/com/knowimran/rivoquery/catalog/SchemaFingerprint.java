package com.knowimran.rivoquery.catalog;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public record SchemaFingerprint(String value) {
    public SchemaFingerprint {
        Objects.requireNonNull(value, "value");
        if (!value.matches("^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("fingerprint is not a valid SHA-256 hex string");
        }
    }

    public static SchemaFingerprint fromSnapshot(SchemaSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(snapshot.workspaceId().value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(snapshot.connectionId().value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(Long.toString(snapshot.discoveredAt().toEpochMilli()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(Integer.toString(snapshot.objectCount()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (SchemaObject object : snapshot.objects()) {
                digest.update(object.rawName().qualified().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(object.canonicalName().qualified().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(object.type().name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (object.description() != null) {
                    digest.update(object.description().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                for (SchemaColumn column : object.columns()) {
                    digest.update(column.name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    digest.update(column.type().name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    digest.update(column.classification().name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            return new SchemaFingerprint(bytesToHex(digest.digest()));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
