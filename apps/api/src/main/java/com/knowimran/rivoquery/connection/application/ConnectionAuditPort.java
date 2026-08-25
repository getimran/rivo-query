package com.knowimran.rivoquery.connection.application;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Instant;
import java.util.Objects;

public interface ConnectionAuditPort {
    void append(Event event);

    record Event(
            WorkspaceId workspaceId,
            ActorId actorId,
            ConnectionId connectionId,
            AuditRequestId requestId,
            ConnectionAuditAction action,
            ConnectionAuditOutcome outcome,
            ConnectionAuditCategory category,
            Instant occurredAt) {
        public Event {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(connectionId, "connectionId");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
