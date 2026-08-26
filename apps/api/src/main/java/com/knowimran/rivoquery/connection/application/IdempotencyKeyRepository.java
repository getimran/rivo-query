package com.knowimran.rivoquery.connection.application;

import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Optional;

/**
 * Backs the {@code idempotency_keys} table (schema added by P2-060, wired
 * for the first time by P2-130). Scoped by {@code (workspace_id, actor_id,
 * idempotency_key)} exactly as the table's primary key requires.
 *
 * <p>{@link #tryReserve}/{@link #complete}/{@link #rollback} together
 * implement a reserve-before-mutate protocol (post-review fix - see {@code
 * IdempotencyRecord}'s own Javadoc): a caller reserves a key <em>before</em>
 * attempting its mutation (relying on this table's primary key for atomic
 * create-only semantics), then either completes or rolls back the
 * reservation once the mutation's outcome is known, never leaving a
 * concurrent racer able to observe "no reservation exists yet" for the same
 * key at the same time.
 */
public interface IdempotencyKeyRepository {
    Optional<IdempotencyRecord> find(WorkspaceId workspaceId, ActorId actorId, String key);

    /**
     * Attempts to durably reserve {@code record} (expected to be {@link
     * IdempotencyRecord#pending}). Returns {@code true} if this is the first
     * reservation for {@code (workspaceId, actorId, key)}; {@code false} if a
     * row already exists (the caller must then inspect it via {@link #find}:
     * a not-yet-{@code completed} row means another request is genuinely
     * mid-flight right now; a {@code completed} row is safe to compare
     * fingerprints against and potentially replay).
     */
    boolean tryReserve(IdempotencyRecord record);

    /**
     * Marks a previously-reserved key as durably resolved, attaching the
     * durable operation id created by the mutation, if any. Must only be
     * called for a row this same request successfully reserved via {@link
     * #tryReserve}.
     */
    void complete(WorkspaceId workspaceId, ActorId actorId, String key, Optional<String> operationId);

    /**
     * Removes a reservation this same request made but whose mutation then
     * failed with a definite, no-side-effect outcome (a legitimate client
     * retry after such a failure must not be mistaken for a duplicate). Only
     * ever removes a row that is still {@code completed = false}; a
     * genuinely completed row - which by definition this request cannot have
     * been the one to reserve - is never touched.
     */
    void rollback(WorkspaceId workspaceId, ActorId actorId, String key);
}
