package com.knowimran.rivoquery.interfaceapi.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.application.IdempotencyKeyRepository;
import com.knowimran.rivoquery.connection.application.IdempotencyRecord;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdempotencyGuardTest {

    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final ActorId ACTOR = new ActorId("act_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final String KEY = "idem-key-1234567890abcdef";
    private static final String FINGERPRINT_1 = "a".repeat(64);
    private static final String FINGERPRINT_2 = "b".repeat(64);

    @Test
    void freshKeyIsReservedAndNotAReplay() {
        var guard = new IdempotencyGuard(new FakeIdempotencyKeyRepository());

        assertThat(guard.reserve(WORKSPACE, ACTOR, KEY, "createConnection", FINGERPRINT_1)).isEmpty();
    }

    @Test
    void exactReplayAfterCompletionIsShortCircuited() {
        var repository = new FakeIdempotencyKeyRepository();
        var guard = new IdempotencyGuard(repository);
        assertThat(guard.reserve(WORKSPACE, ACTOR, KEY, "createConnection", FINGERPRINT_1)).isEmpty();
        guard.complete(WORKSPACE, ACTOR, KEY, Optional.of("op_1"));

        var replay = guard.reserve(WORKSPACE, ACTOR, KEY, "createConnection", FINGERPRINT_1);

        assertThat(replay).isPresent();
        assertThat(replay.orElseThrow().operationId()).contains("op_1");
        assertThat(replay.orElseThrow().completed()).isTrue();
    }

    @Test
    void reuseWithADifferentFingerprintAfterCompletionIsRejected() {
        var repository = new FakeIdempotencyKeyRepository();
        var guard = new IdempotencyGuard(repository);
        assertThat(guard.reserve(WORKSPACE, ACTOR, KEY, "createConnection", FINGERPRINT_1)).isEmpty();
        guard.complete(WORKSPACE, ACTOR, KEY, Optional.empty());

        assertThatThrownBy(() -> guard.reserve(WORKSPACE, ACTOR, KEY, "createConnection", FINGERPRINT_2))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void reuseWithADifferentFingerprintWhileStillInFlightIsRejected() {
        var repository = new FakeIdempotencyKeyRepository();
        var guard = new IdempotencyGuard(repository);
        assertThat(guard.reserve(WORKSPACE, ACTOR, KEY, "createConnection", FINGERPRINT_1)).isEmpty();

        assertThatThrownBy(() -> guard.reserve(WORKSPACE, ACTOR, KEY, "createConnection", FINGERPRINT_2))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void aSecondReservationAttemptWithTheSameFingerprintWhileStillInFlightMustWaitRatherThanReplay() {
        var repository = new FakeIdempotencyKeyRepository();
        var guard = new IdempotencyGuard(repository);
        assertThat(guard.reserve(WORKSPACE, ACTOR, KEY, "createConnection", FINGERPRINT_1)).isEmpty();

        // The first request has not completed yet - this must never be
        // treated as a replay (there is no result to hand back), and it must
        // never be allowed to proceed and duplicate the mutation either.
        assertThatThrownBy(() -> guard.reserve(WORKSPACE, ACTOR, KEY, "createConnection", FINGERPRINT_1))
                .isInstanceOf(IdempotencyKeyInFlightException.class);
    }

    @Test
    void rollbackReleasesTheKeyForAFreshReservation() {
        var repository = new FakeIdempotencyKeyRepository();
        var guard = new IdempotencyGuard(repository);
        assertThat(guard.reserve(WORKSPACE, ACTOR, KEY, "createConnection", FINGERPRINT_1)).isEmpty();

        guard.rollback(WORKSPACE, ACTOR, KEY);

        // A legitimate retry after a no-side-effect failure is fresh again,
        // even with the exact same fingerprint.
        assertThat(guard.reserve(WORKSPACE, ACTOR, KEY, "createConnection", FINGERPRINT_1)).isEmpty();
    }

    @Test
    void rollbackNeverRemovesACompletedReservation() {
        var repository = new FakeIdempotencyKeyRepository();
        var guard = new IdempotencyGuard(repository);
        assertThat(guard.reserve(WORKSPACE, ACTOR, KEY, "createConnection", FINGERPRINT_1)).isEmpty();
        guard.complete(WORKSPACE, ACTOR, KEY, Optional.of("op_1"));

        // A stray/late rollback call (e.g. a caller bug) must never discard
        // an already-completed, replay-safe result.
        guard.rollback(WORKSPACE, ACTOR, KEY);

        var replay = guard.reserve(WORKSPACE, ACTOR, KEY, "createConnection", FINGERPRINT_1);
        assertThat(replay).isPresent();
        assertThat(replay.orElseThrow().operationId()).contains("op_1");
    }

    @Test
    void completionFailureIsBestEffortAndNeverThrows() {
        var guard = new IdempotencyGuard(new ThrowingIdempotencyKeyRepository());

        guard.complete(WORKSPACE, ACTOR, KEY, Optional.empty());
    }

    @Test
    void rollbackFailureIsBestEffortAndNeverThrows() {
        var guard = new IdempotencyGuard(new ThrowingIdempotencyKeyRepository());

        guard.rollback(WORKSPACE, ACTOR, KEY);
    }

    /**
     * B2 regression: two concurrent callers racing under the exact same
     * fresh key must never both be told "you hold the fresh reservation" -
     * exactly one must get {@link Optional#empty()} (proceed with the
     * mutation) and the other must be rejected (never silently allowed to
     * duplicate the mutation). Uses a real {@link CountDownLatch} barrier so
     * both threads call {@code reserve} at genuinely the same time, backed
     * by a {@link ConcurrentHashMap}-based fake whose {@code tryReserve}
     * mirrors the production {@code JdbcIdempotencyKeyRepository}'s
     * unique-constraint-backed atomicity (a single {@code putIfAbsent}).
     */
    @Test
    void concurrentReservationAttemptsForTheSameFreshKeyNeverBothSucceed() throws Exception {
        var repository = new FakeIdempotencyKeyRepository();
        var guard = new IdempotencyGuard(repository);
        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger freshReservations = new AtomicInteger();
        List<Exception> unexpectedFailures = new CopyOnWriteArrayList<>();

        try {
            for (int i = 0; i < racers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        Optional<IdempotencyRecord> outcome =
                                guard.reserve(WORKSPACE, ACTOR, KEY, "createConnection", FINGERPRINT_1);
                        if (outcome.isEmpty()) {
                            freshReservations.incrementAndGet();
                        }
                    } catch (IdempotencyKeyInFlightException expected) {
                        // Every loser must see exactly this - proceeding to
                        // execute the mutation anyway would be the B2 bug.
                    } catch (Exception unexpected) {
                        unexpectedFailures.add(unexpected);
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(unexpectedFailures).isEmpty();
        assertThat(freshReservations.get()).isEqualTo(1);
    }

    private static final class FakeIdempotencyKeyRepository implements IdempotencyKeyRepository {
        private final ConcurrentMap<String, IdempotencyRecord> byKey = new ConcurrentHashMap<>();

        private static String key(WorkspaceId workspaceId, ActorId actorId, String key) {
            return workspaceId.value() + "/" + actorId.value() + "/" + key;
        }

        @Override
        public Optional<IdempotencyRecord> find(WorkspaceId workspaceId, ActorId actorId, String key) {
            return Optional.ofNullable(byKey.get(key(workspaceId, actorId, key)));
        }

        @Override
        public boolean tryReserve(IdempotencyRecord record) {
            return byKey.putIfAbsent(key(record.workspaceId(), record.actorId(), record.key()), record) == null;
        }

        @Override
        public void complete(WorkspaceId workspaceId, ActorId actorId, String key, Optional<String> operationId) {
            byKey.computeIfPresent(key(workspaceId, actorId, key), (mapKey, existing) -> new IdempotencyRecord(
                    existing.workspaceId(), existing.actorId(), existing.key(), existing.action(),
                    existing.requestFingerprint(), operationId.or(existing::operationId), true));
        }

        @Override
        public void rollback(WorkspaceId workspaceId, ActorId actorId, String key) {
            byKey.computeIfPresent(key(workspaceId, actorId, key),
                    (mapKey, existing) -> existing.completed() ? existing : null);
        }
    }

    private static final class ThrowingIdempotencyKeyRepository implements IdempotencyKeyRepository {
        @Override
        public Optional<IdempotencyRecord> find(WorkspaceId workspaceId, ActorId actorId, String key) {
            return Optional.empty();
        }

        @Override
        public boolean tryReserve(IdempotencyRecord record) {
            throw new RuntimeException("simulated durability outage");
        }

        @Override
        public void complete(WorkspaceId workspaceId, ActorId actorId, String key, Optional<String> operationId) {
            throw new RuntimeException("simulated durability outage");
        }

        @Override
        public void rollback(WorkspaceId workspaceId, ActorId actorId, String key) {
            throw new RuntimeException("simulated durability outage");
        }
    }
}
