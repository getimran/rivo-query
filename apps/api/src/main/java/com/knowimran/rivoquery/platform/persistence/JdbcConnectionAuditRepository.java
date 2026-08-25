package com.knowimran.rivoquery.platform.persistence;

import com.knowimran.rivoquery.connection.application.ConnectionAuditPort;
import java.sql.Timestamp;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Append-only persistence for the metadata-only connection lifecycle audit
 * trail. This adapter exposes only {@link #append(Event)}; there is no
 * update or delete method here, and {@code connection_lifecycle_audit_events}
 * additionally has a database-level {@code BEFORE UPDATE OR DELETE} trigger
 * that rejects any mutation, so the append-only guarantee holds even against
 * a direct SQL client. Only actor/tenant/connection/request/action/outcome/
 * category/timestamp columns exist on this table; no secret, credential, or
 * raw driver-error column is ever written here.
 */
@Component
public final class JdbcConnectionAuditRepository implements ConnectionAuditPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcConnectionAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public void append(Event event) {
        Objects.requireNonNull(event, "event");
        jdbcTemplate.update(
                """
                INSERT INTO connection_lifecycle_audit_events
                    (workspace_id, actor_id, connection_id, request_id, action, outcome, category, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.workspaceId().value(),
                event.actorId().value(),
                event.connectionId().value(),
                event.requestId().value(),
                event.action().name(),
                event.outcome().name(),
                event.category().name(),
                Timestamp.from(event.occurredAt()));
    }
}
