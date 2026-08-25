package com.knowimran.rivoquery.connection.application.secret;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DestructibleSecretMaterial implements AutoCloseable {
    private final byte[] bytes;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DestructibleSecretMaterial(byte[] source) {
        Objects.requireNonNull(source, "source");
        if (source.length == 0) {
            throw new IllegalArgumentException("secret material must not be empty");
        }
        this.bytes = source.clone();
    }

    public <T> T consume(SecretConsumer<T> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (!closed.compareAndSet(false, true)) {
            throw new IllegalStateException("secret material is unavailable");
        }
        try {
            return consumer.accept(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "DestructibleSecretMaterial[REDACTED]";
    }

    @FunctionalInterface
    public interface SecretConsumer<T> {
        T accept(byte[] borrowedBytes);
    }
}
