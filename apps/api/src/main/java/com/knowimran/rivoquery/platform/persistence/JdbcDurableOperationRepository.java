package com.knowimran.rivoquery.platform.persistence;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.connection.application.DurableOperation;
import com.knowimran.rivoquery.connection.application.DurableOperationRepository;
import com.knowimran.rivoquery.connection.application.IllegalDurableOperationTransitionException;
import com.knowimran.rivoquery.connection.application.OperationAction;
import com.knowimran.rivoquery.connection.application.OperationState;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped durable-operation bookkeeping backing the P2-100 saga work.
 * {@link #save} is an idempotent upsert keyed by {@code (workspace_id,
 * operation_id)}: a brand-new operation is always accepted, and an existing
 * row only accepts a write that is a legal progression - the stored row must
 * not already be terminal ({@code SUCCEEDED}/{@code FAILED}), the actor may
 * never change, and {@code cancelled} may only ever go from {@code false} to
 * {@code true}, never back. A write that violates one of those rules throws
 * {@link IllegalDurableOperationTransitionException} unless it is a
 * byte-for-byte identical rewrite of the row already stored (a legitimate
 * idempotent retry), which is accepted as a no-op. The domain type carries
 * no separate version field, so this guard - not an optimistic-concurrency
 * column - is what prevents rewriting a completed operation.
 */
// Not final: Spring's default proxy-target-class=true AOP configuration
// requires a CGLIB subclass to support @Transactional on this bean.
@Component
public class JdbcDurableOperationRepository implements DurableOperationRepository {

    private static final String FIND_QUERY = """
            SELECT operation_id, workspace_id, actor_id, connection_id, action, state, cancelled
            FROM durable_operations
            WHERE workspace_id = ? AND operation_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcDurableOperationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public Optional<DurableOperation> findByWorkspaceAndId(WorkspaceId workspaceId, OperationId operationId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(operationId, "operationId");
        List<DurableOperation> rows = jdbcTemplate.query(
                FIND_QUERY,
                (resultSet, rowNumber) -> {
                    String connectionIdValue = resultSet.getString("connection_id");
                    return new DurableOperation(
                            new OperationId(resultSet.getString("operation_id")),
                            new WorkspaceId(resultSet.getString("workspace_id")),
                            new ActorId(resultSet.getString("actor_id")),
                            connectionIdValue == null
                                    ? Optional.empty()
                                    : Optional.of(new ConnectionId(connectionIdValue)),
                            OperationAction.valueOf(resultSet.getString("action")),
                            OperationState.valueOf(resultSet.getString("state")),
                            resultSet.getBoolean("cancelled"));
                },
                workspaceId.value(), operationId.value());
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public void save(DurableOperation operation) {
        Objects.requireNonNull(operation, "operation");

        // ON CONFLICT DO NOTHING is atomic and race-safe for the "this is a
        // brand-new operation" case; existing rows fall through to the
        // guarded conditional UPDATE below.
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO durable_operations
                    (workspace_id, operation_id, actor_id, connection_id, action, state, cancelled)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (workspace_id, operation_id) DO NOTHING
                """,
                operation.workspaceId().value(),
                operation.id().value(),
                operation.actorId().value(),
                operation.connectionId().map(ConnectionId::value).orElse(null),
                operation.action().name(),
                operation.state().name(),
                operation.cancelled());
        if (inserted == 1) {
            return;
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE durable_operations
                   SET connection_id = ?, action = ?, state = ?, cancelled = ?, updated_at = now()
                 WHERE workspace_id = ? AND operation_id = ? AND actor_id = ?
                   AND state NOT IN ('SUCCEEDED', 'FAILED')
                   AND (NOT cancelled OR ? = TRUE)
                """,
                operation.connectionId().map(ConnectionId::value).orElse(null),
                operation.action().name(),
                operation.state().name(),
                operation.cancelled(),
                operation.workspaceId().value(),
                operation.id().value(),
                operation.actorId().value(),
                operation.cancelled());
        if (updated == 1) {
            return;
        }

        if (isIdenticalToStoredRow(operation)) {
            return;
        }
        throw new IllegalDurableOperationTransitionException();
    }

    private boolean isIdenticalToStoredRow(DurableOperation operation) {
        List<Boolean> matches = jdbcTemplate.query(
                """
                SELECT 1 FROM durable_operations
                 WHERE workspace_id = ? AND operation_id = ? AND actor_id = ?
                   AND connection_id IS NOT DISTINCT FROM ?
                   AND action = ? AND state = ? AND cancelled = ?
                """,
                (resultSet, rowNumber) -> Boolean.TRUE,
                operation.workspaceId().value(),
                operation.id().value(),
                operation.actorId().value(),
                operation.connectionId().map(ConnectionId::value).orElse(null),
                operation.action().name(),
                operation.state().name(),
                operation.cancelled());
        return !matches.isEmpty();
    }
}
