package com.knowimran.rivoquery.connection.application.lifecycle;

import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.connection.application.secret.DurableSecretAuthorization;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process, non-durable {@link DurableSecretAuthorization} implementation.
 *
 * <p>{@code SecretCapabilityAuthority.stage}/{@code consumeForOperation}/
 * {@code revokeForOperation} each require an exactly-matching, pre-registered
 * {@link DurableSecretAuthorization.AuthorizedOperation} to exist before they
 * will touch the secret store at all - this is the mechanism that stops any
 * caller other than the lifecycle saga itself (which alone can register one)
 * from staging, reading, or revoking secret material. This registry is where
 * the saga registers that authorization immediately before each secret-store
 * call and releases it immediately after, whether the call succeeded or
 * failed.
 *
 * <p><b>Known limitation, deliberately not fixed in this task:</b> entries
 * live only in this process's heap, not in the control database. If the
 * process crashes between registering an entry and using it, the entry is
 * simply lost - which is safe (the corresponding secret-store call never
 * happened, so there is nothing to reconcile for it), but it also means an
 * entry can never be used to resume a secret-store call across a restart.
 * No schema exists for a durable version of this table (P2-060's migration
 * predates this task and does not include one, and adding one is a new
 * migration outside this task's file scope), so a real, crash-recoverable
 * implementation is left for a follow-up task. A bounded time-to-live is
 * still enforced ({@link #evictExpired()}, called on every registration) so
 * an abandoned entry (e.g. the owning request died without releasing it)
 * cannot accumulate forever.
 */
public final class SecretOperationAuthorizationRegistry implements DurableSecretAuthorization {
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration timeToLive;

    public SecretOperationAuthorizationRegistry(Clock clock, Duration timeToLive) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeToLive = Objects.requireNonNull(timeToLive, "timeToLive");
        if (timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("timeToLive must be positive");
        }
    }

    /**
     * Package-private: {@link ConnectionLifecycleService} (same package) is
     * the only trusted caller - it is the sole component that may mint a
     * {@link AuthorizedOperation} at all. See {@code
     * ArchitectureTest.only_connection_lifecycle_service_calls_secret_operation_authorization_registry_mutators}
     * for the defense-in-depth check that a future bean wiring the concrete
     * type (rather than the {@code DurableSecretAuthorization} interface)
     * cannot mint an arbitrary ACCESS/STAGE/REVOKE authorization for any
     * binding from outside this package.
     */
    void register(AuthorizedOperation operation) {
        Objects.requireNonNull(operation, "operation");
        evictExpired();
        entries.put(
                new Key(operation.workspaceId(), operation.operationId()),
                new Entry(operation, clock.instant().plus(timeToLive)));
    }

    /** Package-private: see {@link #register}. */
    void release(WorkspaceId workspaceId, OperationId operationId) {
        entries.remove(new Key(workspaceId, operationId));
    }

    @Override
    public Optional<AuthorizedOperation> findByWorkspaceAndId(WorkspaceId workspaceId, OperationId operationId) {
        Entry entry = entries.get(new Key(workspaceId, operationId));
        if (entry == null || clock.instant().isAfter(entry.expiresAt)) {
            return Optional.empty();
        }
        return Optional.of(entry.operation);
    }

    private void evictExpired() {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> now.isAfter(entry.expiresAt));
    }

    private record Key(WorkspaceId workspaceId, OperationId operationId) {}

    private record Entry(AuthorizedOperation operation, Instant expiresAt) {}
}
