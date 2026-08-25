package com.knowimran.rivoquery.platform.secrets;

import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.secret.SecretLease;
import com.knowimran.rivoquery.connection.application.secret.SecretMaterialConsumer;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The lease every {@code SecretStore} adapter in this package returns.
 * {@code AutoCloseable}, thread-confined to the thread that obtained it,
 * single-consumption, and valid only until {@code deadline} - checked again
 * here at use time rather than trusted from the caller, so a lease that sits
 * unused past its bound cannot release plaintext. Exposes material only to a
 * bounded callback; never returns a reusable array/String/stream. Closing
 * (directly or via consumption) is idempotent and zeroes the owned buffer on
 * every path, including callback failure.
 */
final class BoundedSecretLease implements SecretLease {
    private final byte[] material;
    private final Deadline deadline;
    private final Clock clock;
    private final Thread ownerThread;
    private final AtomicBoolean finished = new AtomicBoolean();

    BoundedSecretLease(byte[] material, Deadline deadline, Clock clock) {
        this.material = Objects.requireNonNull(material, "material");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownerThread = Thread.currentThread();
    }

    @Override
    public void consume(SecretMaterialConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (Thread.currentThread() != ownerThread) {
            throw new SecretLeaseUnusableException();
        }
        if (!finished.compareAndSet(false, true)) {
            throw new SecretLeaseUnusableException();
        }
        try {
            if (deadline.isExpired(clock)) {
                throw new SecretLeaseUnusableException();
            }
            consumer.use(material);
        } finally {
            Arrays.fill(material, (byte) 0);
        }
    }

    @Override
    public void close() {
        if (finished.compareAndSet(false, true)) {
            Arrays.fill(material, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "SecretLease[REDACTED]";
    }
}
