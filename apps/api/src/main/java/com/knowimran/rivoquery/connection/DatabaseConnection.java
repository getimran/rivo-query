package com.knowimran.rivoquery.connection;

import com.knowimran.rivoquery.connection.application.secret.BoundSecretReference;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class DatabaseConnection {
    private final WorkspaceId workspaceId;
    private final ConnectionId id;
    private final DatabaseKind kind;
    private final ConnectionState state;
    private final ConnectionVersion version;
    private final long latestSecretVersion;
    private final BoundSecretReference currentSecret;
    private final Instant secretRetireAt;

    private DatabaseConnection(
            WorkspaceId workspaceId,
            ConnectionId id,
            DatabaseKind kind,
            ConnectionState state,
            ConnectionVersion version,
            long latestSecretVersion,
            BoundSecretReference currentSecret,
            Instant secretRetireAt) {
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.state = Objects.requireNonNull(state, "state");
        this.version = Objects.requireNonNull(version, "version");
        if (latestSecretVersion < 0) {
            throw new IllegalArgumentException("latest secret version must not be negative");
        }
        if (currentSecret != null) {
            currentSecret.binding().requireConnection(workspaceId, id);
            if (currentSecret.binding().version() > latestSecretVersion) {
                throw new IllegalArgumentException("current secret exceeds latest reserved version");
            }
        }
        if ((state == ConnectionState.PENDING_SECRET || state == ConnectionState.DELETING || state == ConnectionState.DELETED)
                && currentSecret != null) {
            throw new IllegalArgumentException("connection state cannot retain a current secret");
        }
        if ((state == ConnectionState.PENDING_VALIDATION
                        || state == ConnectionState.ACTIVE
                        || state == ConnectionState.DISABLED)
                && currentSecret == null) {
            throw new IllegalArgumentException("connection state requires a current secret");
        }
        if (state == ConnectionState.FAILED_VALIDATION && secretRetireAt == null) {
            throw new IllegalArgumentException("failed validation requires a credential retirement instant");
        }
        if (state != ConnectionState.FAILED_VALIDATION && secretRetireAt != null) {
            throw new IllegalArgumentException("credential retirement is valid only after failed validation");
        }
        this.latestSecretVersion = latestSecretVersion;
        this.currentSecret = currentSecret;
        this.secretRetireAt = secretRetireAt;
    }

    public static DatabaseConnection reserve(WorkspaceId workspaceId, ConnectionId id, DatabaseKind kind) {
        return new DatabaseConnection(
                workspaceId, id, kind, ConnectionState.PENDING_SECRET, new ConnectionVersion(1), 0, null, null);
    }

    public static DatabaseConnection rehydrate(
            WorkspaceId workspaceId,
            ConnectionId id,
            DatabaseKind kind,
            ConnectionState state,
            ConnectionVersion version,
            long latestSecretVersion,
            BoundSecretReference currentSecret,
            Instant secretRetireAt) {
        return new DatabaseConnection(
                workspaceId, id, kind, state, version, latestSecretVersion, currentSecret, secretRetireAt);
    }

    public DatabaseConnection transition(ConnectionVersion expectedVersion, ConnectionState target) {
        requireVersion(expectedVersion);
        if (!state.canTransitionTo(target)) {
            throw new InvalidConnectionTransitionException();
        }
        if (target == ConnectionState.FAILED_VALIDATION) {
            // FAILED_VALIDATION always requires a credential-retirement
            // instant, which this generic method has no way to accept;
            // callers must go through validationFailed() instead.
            throw new InvalidConnectionTransitionException();
        }
        var retainedSecret = target == ConnectionState.DELETING ? null : currentSecret;
        if (retainedSecret == null && requiresCurrentSecret(target)) {
            // canTransitionTo() advertises this edge as reachable in the
            // abstract state graph (e.g. FAILED_VALIDATION -> DISABLED while
            // the failing secret is still attached), but this particular
            // instance already lost its current secret (e.g. via
            // detachFailedSecret()), and target unconditionally requires
            // one. Fail closed with the domain's transition-rejection
            // exception rather than letting the constructor's invariant
            // check surface as an IllegalArgumentException.
            throw new InvalidConnectionTransitionException();
        }
        return new DatabaseConnection(
                workspaceId, id, kind, target, version.next(), latestSecretVersion, retainedSecret, null);
    }

    private static boolean requiresCurrentSecret(ConnectionState target) {
        return target == ConnectionState.PENDING_VALIDATION
                || target == ConnectionState.ACTIVE
                || target == ConnectionState.DISABLED;
    }

    public DatabaseConnection validationFailed(ConnectionVersion expectedVersion, Instant retireAt) {
        requireVersion(expectedVersion);
        if (state != ConnectionState.PENDING_VALIDATION
                || retireAt == null) {
            throw new InvalidConnectionTransitionException();
        }
        return new DatabaseConnection(
                workspaceId,
                id,
                kind,
                ConnectionState.FAILED_VALIDATION,
                version.next(),
                latestSecretVersion,
                currentSecret,
                retireAt);
    }

    public DatabaseConnection reserveNextSecretVersion(ConnectionVersion expectedVersion) {
        requireVersion(expectedVersion);
        if (state == ConnectionState.DELETING || state == ConnectionState.DELETED
                || latestSecretVersion == Long.MAX_VALUE) {
            throw new InvalidConnectionTransitionException();
        }
        return new DatabaseConnection(
                workspaceId,
                id,
                kind,
                state,
                version.next(),
                latestSecretVersion + 1,
                currentSecret,
                secretRetireAt);
    }

    public DatabaseConnection attachCurrentSecret(ConnectionVersion expectedVersion, BoundSecretReference reference) {
        requireVersion(expectedVersion);
        if (state == ConnectionState.DELETING || state == ConnectionState.DELETED) {
            throw new InvalidConnectionTransitionException();
        }
        Objects.requireNonNull(reference, "reference").binding().requireConnection(workspaceId, id);
        long currentVersion = currentSecret == null ? 0 : currentSecret.binding().version();
        if (reference.binding().version() != latestSecretVersion
                || reference.binding().version() <= currentVersion) {
            throw new IllegalArgumentException("secret version is not the latest reserved version");
        }
        var nextState = switch (state) {
            case PENDING_SECRET, PENDING_VALIDATION, FAILED_VALIDATION -> ConnectionState.PENDING_VALIDATION;
            case ACTIVE -> ConnectionState.ACTIVE;
            case DISABLED -> ConnectionState.DISABLED;
            case DELETING, DELETED -> throw new InvalidConnectionTransitionException();
        };
        return new DatabaseConnection(
                workspaceId, id, kind, nextState, version.next(), latestSecretVersion, reference, null);
    }

    public DatabaseConnection detachFailedSecret(
            ConnectionVersion expectedVersion, BoundSecretReference expectedSecret) {
        requireVersion(expectedVersion);
        if (state != ConnectionState.FAILED_VALIDATION
                || currentSecret == null
                || !currentSecret.equals(Objects.requireNonNull(expectedSecret, "expectedSecret"))) {
            throw new InvalidConnectionTransitionException();
        }
        return new DatabaseConnection(
                workspaceId,
                id,
                kind,
                state,
                version.next(),
                latestSecretVersion,
                null,
                secretRetireAt);
    }

    public void authorize(ActorContext actor) {
        Objects.requireNonNull(actor, "actor").requireWorkspace(workspaceId);
    }

    public void requireVersion(ConnectionVersion expectedVersion) {
        if (!version.equals(expectedVersion)) {
            throw new StaleConnectionVersionException();
        }
    }

    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    public ConnectionId id() {
        return id;
    }

    public DatabaseKind kind() {
        return kind;
    }

    public ConnectionState state() {
        return state;
    }

    public ConnectionVersion version() {
        return version;
    }

    public long latestSecretVersion() {
        return latestSecretVersion;
    }

    public Optional<BoundSecretReference> currentSecret() {
        return Optional.ofNullable(currentSecret);
    }

    public Optional<Instant> secretRetireAt() {
        return Optional.ofNullable(secretRetireAt);
    }
}
