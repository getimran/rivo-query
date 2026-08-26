package com.knowimran.rivoquery.interfaceapi.support;

import com.knowimran.rivoquery.connection.application.IdempotencyKeyRepository;
import com.knowimran.rivoquery.connection.application.IdempotencyRecord;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Idempotency semantics for every mutating endpoint that declares the
 * {@code Idempotency-Key} header (see docs/IMPLEMENTATION_TASKS.md P2-130
 * evidence for the exact scope decision this codifies).
 *
 * <p><b>Reserve-before-mutate protocol (post-review race-safety fix):</b> a
 * caller must call {@link #reserve} <em>before</em> attempting its mutation,
 * then exactly one of {@link #complete} (the mutation durably succeeded, or
 * failed in a way that still produced a real, durable effect worth
 * remembering) or {@link #rollback} (the mutation failed with a definite,
 * no-side-effect outcome - authorization denial, not-found, stale-version,
 * pre-action-audit failure, a compensated lifecycle failure) once the
 * outcome is known. {@link #reserve} relies on {@code idempotency_keys}'
 * primary key for atomic create-only semantics: two concurrent callers
 * racing under the exact same key can never both observe "no reservation
 * exists yet" - the loser either gets back the winner's already-completed
 * result (a genuine replay) or, if the winner is still mid-flight, {@link
 * IdempotencyKeyInFlightException} (a transient "retry shortly" signal, not
 * a duplicate-mutation risk). This closes the race the original
 * check-after-complete design had: for {@code createConnection} specifically
 * - which mints its own fresh {@code connectionId} per attempt and so has no
 * shared row for an optimistic-concurrency check to fail one of two racing
 * requests against - two concurrent identical requests could previously both
 * run to completion and both stage/promote real secret material.
 *
 * <p>Reusing a key with a different request fingerprint always throws {@link
 * IdempotencyKeyReusedException} (409), matching the OpenAPI contract.
 */
@Component
public final class IdempotencyGuard {

    private static final int MAX_RESERVE_ATTEMPTS = 5;

    private final IdempotencyKeyRepository idempotencyKeys;

    public IdempotencyGuard(IdempotencyKeyRepository idempotencyKeys) {
        this.idempotencyKeys = Objects.requireNonNull(idempotencyKeys, "idempotencyKeys");
    }

    /**
     * Returns empty if {@code key} was freshly, durably reserved by this call
     * (the caller must now run its mutation, then call exactly one of {@link
     * #complete}/{@link #rollback}), or the existing terminal record if
     * {@code key} was already used to completion with the exact same {@code
     * action}/{@code fingerprint} (a replay the caller should short-circuit
     * on without repeating the mutation). Throws {@link
     * IdempotencyKeyInFlightException} if another request is genuinely
     * mid-flight for this key right now, or {@link
     * IdempotencyKeyReusedException} if {@code key} was already used (in
     * flight or completed) with a different {@code action}/{@code
     * fingerprint}.
     */
    public Optional<IdempotencyRecord> reserve(
            WorkspaceId workspaceId, ActorId actorId, String key, String action, String fingerprint) {
        IdempotencyRecord pending = IdempotencyRecord.pending(workspaceId, actorId, key, action, fingerprint);
        for (int attempt = 0; attempt < MAX_RESERVE_ATTEMPTS; attempt++) {
            if (idempotencyKeys.tryReserve(pending)) {
                return Optional.empty();
            }
            Optional<IdempotencyRecord> existing = idempotencyKeys.find(workspaceId, actorId, key);
            if (existing.isEmpty()) {
                // The row we lost the race to was rolled back between our
                // failed INSERT and this SELECT (its own mutation failed
                // with a no-side-effect outcome) - retry our own reservation
                // rather than treating this as a conflict.
                continue;
            }
            IdempotencyRecord record = existing.orElseThrow();
            if (!record.action().equals(action) || !record.requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyReusedException();
            }
            if (!record.completed()) {
                throw new IdempotencyKeyInFlightException();
            }
            return existing;
        }
        // Exhausted retries under sustained contention on the exact same key
        // - still safe (never duplicates), just cannot make progress right
        // now; the same "retry shortly" signal as a genuine in-flight race.
        throw new IdempotencyKeyInFlightException();
    }

    /**
     * Marks a key this same request reserved (via {@link #reserve} returning
     * empty) as durably resolved. Best-effort: a failure here must never fail
     * an otherwise-successful mutation - but unlike the pre-fix design,
     * worst case is no longer "a later exact replay is treated as fresh"; it
     * is now "a later exact replay under this key sees {@link
     * IdempotencyKeyInFlightException} until {@code expires_at} lapses" (see
     * the {@code idempotency_keys} migration's own comment) - still safe
     * (never a duplicate mutation), just less available in this narrow edge
     * case, which is the correct trade-off for closing the underlying race.
     */
    public void complete(WorkspaceId workspaceId, ActorId actorId, String key, Optional<String> operationId) {
        try {
            idempotencyKeys.complete(workspaceId, actorId, key, operationId);
        } catch (RuntimeException completionFailure) {
            // Best-effort: see method Javadoc.
        }
    }

    /**
     * Releases a key this same request reserved (via {@link #reserve}
     * returning empty) whose mutation then failed with a definite,
     * no-side-effect outcome, so a legitimate client retry is never mistaken
     * for a duplicate. Best-effort and silent: called from a {@code catch}
     * block around an in-flight failure that is already being propagated -
     * a rollback failure must never replace or suppress that original
     * exception.
     */
    public void rollback(WorkspaceId workspaceId, ActorId actorId, String key) {
        try {
            idempotencyKeys.rollback(workspaceId, actorId, key);
        } catch (RuntimeException rollbackFailure) {
            // Best-effort: see method Javadoc.
        }
    }
}
