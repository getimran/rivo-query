package com.knowimran.rivoquery.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.application.Deadline;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BoundedSecretLeaseTest {
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void consumeHandsMaterialToTheCallbackExactlyOnceThenZeroesIt() {
        byte[] backing = "canary-plaintext".getBytes();
        var lease = new BoundedSecretLease(backing, new Deadline(NOW.plusSeconds(30)), CLOCK);
        var seen = new byte[1][];

        lease.consume(bytes -> seen[0] = Arrays.copyOf(bytes, bytes.length));

        assertThat(new String(seen[0])).isEqualTo("canary-plaintext");
        assertThat(backing).containsOnly((byte) 0);
    }

    @Test
    void secondConsumeAttemptFailsClosedAndNeverInvokesTheCallback() {
        var lease = new BoundedSecretLease("material".getBytes(), new Deadline(NOW.plusSeconds(30)), CLOCK);
        lease.consume(bytes -> { });

        var invoked = new AtomicBoolean();
        assertThatThrownBy(() -> lease.consume(bytes -> invoked.set(true)))
                .isInstanceOf(SecretLeaseUnusableException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(ex, "material"));
        assertThat(invoked).isFalse();
    }

    @Test
    void consumeAfterTheDeadlineHasElapsedFailsClosedWithoutInvokingTheCallbackAndStillZeroes() {
        byte[] backing = "material".getBytes();
        var alreadyExpired = new Deadline(NOW.minusSeconds(1));
        var lease = new BoundedSecretLease(backing, alreadyExpired, CLOCK);

        var invoked = new AtomicBoolean();
        assertThatThrownBy(() -> lease.consume(bytes -> invoked.set(true)))
                .isInstanceOf(SecretLeaseUnusableException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(ex, "material"));

        assertThat(invoked).isFalse();
        assertThat(backing).containsOnly((byte) 0);
    }

    @Test
    void closeWithoutConsumingZeroesMaterialAndIsIdempotent() {
        byte[] backing = "material".getBytes();
        var lease = new BoundedSecretLease(backing, new Deadline(NOW.plusSeconds(30)), CLOCK);

        lease.close();
        assertThat(backing).containsOnly((byte) 0);

        lease.close(); // idempotent, must not throw
    }

    @Test
    void closeAfterConsumeIsANoOp() {
        var lease = new BoundedSecretLease("material".getBytes(), new Deadline(NOW.plusSeconds(30)), CLOCK);
        lease.consume(bytes -> { });

        lease.close(); // must not throw, already terminal
    }

    @Test
    void consumeFromAnotherThreadFailsClosedWithoutInvokingTheCallback() throws InterruptedException {
        var lease = new BoundedSecretLease("material".getBytes(), new Deadline(NOW.plusSeconds(30)), CLOCK);
        var invoked = new AtomicBoolean();
        var threwExpected = new AtomicBoolean();
        var caught = new SecretLeaseUnusableException[1];

        Thread other = new Thread(() -> {
            try {
                lease.consume(bytes -> invoked.set(true));
            } catch (SecretLeaseUnusableException expected) {
                threwExpected.set(true);
                caught[0] = expected;
            }
        });
        other.start();
        other.join(Duration.ofSeconds(5).toMillis());

        assertThat(threwExpected).isTrue();
        assertThat(invoked).isFalse();
        SecretStoreTestFixtures.assertNoSecretLeakage(caught[0], "material");
    }

    @Test
    void callbackFailureStillZeroesMaterialAndMarksTheLeaseTerminal() {
        byte[] backing = "material".getBytes();
        var lease = new BoundedSecretLease(backing, new Deadline(NOW.plusSeconds(30)), CLOCK);

        assertThatThrownBy(() -> lease.consume(bytes -> {
            throw new IllegalStateException("callback failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(backing).containsOnly((byte) 0);
        assertThatThrownBy(() -> lease.consume(bytes -> { }))
                .isInstanceOf(SecretLeaseUnusableException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(ex, "material"));
    }

    @Test
    void toStringIsRedacted() {
        var lease = new BoundedSecretLease("material".getBytes(), new Deadline(NOW.plusSeconds(30)), CLOCK);
        assertThat(lease.toString()).isEqualTo("SecretLease[REDACTED]");
    }
}
