package com.knowimran.rivoquery.platform.persistence;

import com.knowimran.rivoquery.catalog.SchemaRefreshOperation;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshOperationRepository;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSchemaRefreshOperationRepository implements SchemaRefreshOperationRepository {

    private static final String INSERT = """
            INSERT INTO schema_refresh_operations
                (operation_id, workspace_id, connection_id, state, claimed_by,
                 claimed_at, lease_expires_at, fencing_token, attempt, max_attempts,
                 retry_backoff_ms, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_BY_ID = """
            SELECT operation_id, workspace_id, connection_id, state, claimed_by,
                   claimed_at, lease_expires_at, fencing_token, attempt, max_attempts,
                   retry_backoff_ms, created_at, updated_at
            FROM schema_refresh_operations
            WHERE operation_id = ?
            """;

    private static final String UPDATE_STATE = """
            UPDATE schema_refresh_operations
            SET state = ?, updated_at = now()
            WHERE operation_id = ?
            """;

    private static final RowMapper<SchemaRefreshOperation> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        return new SchemaRefreshOperation(
                rs.getString("operation_id"),
                new WorkspaceId(rs.getString("workspace_id")),
                new ConnectionId(rs.getString("connection_id")),
                rs.getString("state"),
                rs.getString("claimed_by"),
                rs.getTimestamp("claimed_at") != null ? rs.getTimestamp("claimed_at").toInstant() : null,
                rs.getTimestamp("lease_expires_at") != null ? rs.getTimestamp("lease_expires_at").toInstant() : null,
                rs.getLong("fencing_token"),
                rs.getInt("attempt"),
                rs.getInt("max_attempts"),
                rs.getInt("retry_backoff_ms"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    };

    private final JdbcTemplate jdbcTemplate;

    public JdbcSchemaRefreshOperationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public void insert(SchemaRefreshOperation operation) {
        Objects.requireNonNull(operation, "operation");
        jdbcTemplate.update(INSERT, ps -> {
            ps.setString(1, operation.operationId());
            ps.setString(2, operation.workspaceId().value());
            ps.setString(3, operation.connectionId().value());
            ps.setString(4, operation.state());
            ps.setString(5, operation.claimedBy());
            ps.setTimestamp(6, operation.claimedAt() != null ? Timestamp.from(operation.claimedAt()) : null);
            ps.setTimestamp(7, operation.leaseExpiresAt() != null ? Timestamp.from(operation.leaseExpiresAt()) : null);
            ps.setLong(8, operation.fencingToken());
            ps.setInt(9, operation.attempt());
            ps.setInt(10, operation.maxAttempts());
            ps.setInt(11, operation.retryBackoffMs());
            ps.setTimestamp(12, Timestamp.from(operation.createdAt()));
            ps.setTimestamp(13, Timestamp.from(operation.updatedAt()));
        });
    }

    @Override
    public Optional<SchemaRefreshOperation> findById(String operationId) {
        Objects.requireNonNull(operationId, "operationId");
        List<SchemaRefreshOperation> rows = jdbcTemplate.query(FIND_BY_ID, ROW_MAPPER, operationId);
        return rows.stream().findFirst();
    }

    @Override
    public int updateState(String operationId, String state) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(state, "state");
        return jdbcTemplate.update(UPDATE_STATE, state, operationId);
    }
}
