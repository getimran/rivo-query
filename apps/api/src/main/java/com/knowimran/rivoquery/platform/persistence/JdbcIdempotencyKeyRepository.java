package com.knowimran.rivoquery.platform.persistence;

import com.knowimran.rivoquery.connection.application.IdempotencyKeyRepository;
import com.knowimran.rivoquery.connection.application.IdempotencyRecord;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Backs {@code idempotency_keys} (P2-060 schema, wired for the first time by
 * P2-130, race-safety-hardened by post-review follow-up). {@code tryReserve}
 * relies on the table's primary key ({@code workspace_id, actor_id,
 * idempotency_key}) for atomic create-only semantics: a concurrent duplicate
 * reservation attempt fails with a unique violation, translated here to
 * {@code false} rather than propagating a raw driver exception. {@code
 * completed_at} distinguishes a live in-flight reservation ({@code NULL})
 * from a terminal, replay-safe one (non-{@code NULL}) - see {@link
 * IdempotencyKeyRepository}'s own Javadoc for the full protocol.
 */
// Not final: Spring's default proxy-target-class=true AOP configuration
// requires a CGLIB subclass for every @Repository bean (persistence
// exception translation applies regardless of @Transactional use).
@Repository
public class JdbcIdempotencyKeyRepository implements IdempotencyKeyRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcIdempotencyKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public Optional<IdempotencyRecord> find(WorkspaceId workspaceId, ActorId actorId, String key) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(key, "key");
        List<IdempotencyRecord> rows = jdbcTemplate.query(
                "SELECT action, request_fingerprint, operation_id, completed_at FROM idempotency_keys "
                        + "WHERE workspace_id = ? AND actor_id = ? AND idempotency_key = ?",
                (resultSet, rowNumber) -> new IdempotencyRecord(
                        workspaceId, actorId, key,
                        resultSet.getString("action"),
                        resultSet.getString("request_fingerprint"),
                        Optional.ofNullable(resultSet.getString("operation_id")),
                        resultSet.getObject("completed_at") != null),
                workspaceId.value(), actorId.value(), key);
        return rows.stream().findFirst();
    }

    @Override
    public boolean tryReserve(IdempotencyRecord record) {
        Objects.requireNonNull(record, "record");
        try {
            jdbcTemplate.update(
                    "INSERT INTO idempotency_keys "
                            + "(workspace_id, actor_id, idempotency_key, action, request_fingerprint, operation_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    record.workspaceId().value(), record.actorId().value(), record.key(), record.action(),
                    record.requestFingerprint(), record.operationId().orElse(null));
            return true;
        } catch (DataIntegrityViolationException violation) {
            if (isUniqueViolation(violation)) {
                return false;
            }
            throw violation;
        }
    }

    /**
     * Only a unique/PK violation (PostgreSQL SQLState {@code 23505}) means
     * "another reservation already exists under this key" and is safely
     * translated to {@code false}. Any other integrity failure (FK, CHECK,
     * NOT NULL) is a genuine defect and must propagate, not be silently
     * misreported as an already-reserved key that later surfaces as a
     * misleading {@code 409 IDEMPOTENCY_KEY_IN_FLIGHT}.
     */
    private static boolean isUniqueViolation(DataIntegrityViolationException violation) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void complete(WorkspaceId workspaceId, ActorId actorId, String key, Optional<String> operationId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operationId, "operationId");
        jdbcTemplate.update(
                "UPDATE idempotency_keys SET completed_at = now(), operation_id = COALESCE(?, operation_id) "
                        + "WHERE workspace_id = ? AND actor_id = ? AND idempotency_key = ?",
                operationId.orElse(null), workspaceId.value(), actorId.value(), key);
    }

    @Override
    public void rollback(WorkspaceId workspaceId, ActorId actorId, String key) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(key, "key");
        jdbcTemplate.update(
                "DELETE FROM idempotency_keys "
                        + "WHERE workspace_id = ? AND actor_id = ? AND idempotency_key = ? AND completed_at IS NULL",
                workspaceId.value(), actorId.value(), key);
    }
}
