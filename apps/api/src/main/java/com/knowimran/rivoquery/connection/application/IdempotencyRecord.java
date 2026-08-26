package com.knowimran.rivoquery.connection.application;

import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A reserved idempotency key row (see {@code idempotency_keys}, added by
 * P2-060's migration, first wired to a port/adapter by P2-130). {@code
 * action} is the OpenAPI {@code operationId} string (e.g. {@code
 * "disableConnection"}), not {@link OperationAction}, since several
 * idempotent mutations (disable/enable/permission replace/revoke) have no
 * {@link OperationAction} member at all. {@code requestFingerprint} is a
 * lowercase-hex SHA-256 digest of the canonicalized request; a stored key
 * reused with a different fingerprint is a conflict.
 *
 * <p>{@code completed} distinguishes a row reserved-but-not-yet-resolved
 * (another request is genuinely mid-flight for this exact key right now)
 * from a row that reached a terminal outcome (safe to compare fingerprints
 * against and replay) - see {@link
 * com.knowimran.rivoquery.interfaceapi.support.IdempotencyGuard}'s own
 * Javadoc for the full reserve/complete/rollback protocol this closes a
 * duplicate-mutation race in.
 */
public record IdempotencyRecord(
        WorkspaceId workspaceId,
        ActorId actorId,
        String key,
        String action,
        String requestFingerprint,
        Optional<String> operationId,
        boolean completed) {
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$");
    private static final Pattern FINGERPRINT_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    public IdempotencyRecord {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        operationId = Objects.requireNonNull(operationId, "operationId");
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("idempotency key is not canonical");
        }
        if (action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        if (!FINGERPRINT_PATTERN.matcher(requestFingerprint).matches()) {
            throw new IllegalArgumentException("request fingerprint must be a lowercase hex sha-256 digest");
        }
    }

    /** A fresh, not-yet-resolved reservation: {@code completed = false}, no operation id yet. */
    public static IdempotencyRecord pending(
            WorkspaceId workspaceId, ActorId actorId, String key, String action, String requestFingerprint) {
        return new IdempotencyRecord(workspaceId, actorId, key, action, requestFingerprint, Optional.empty(), false);
    }
}
