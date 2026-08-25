package com.knowimran.rivoquery.connection.application.lifecycle;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.application.AuditRequestId;
import com.knowimran.rivoquery.connection.application.ConnectionAuditAction;
import com.knowimran.rivoquery.connection.application.ConnectionAuditCategory;
import com.knowimran.rivoquery.connection.application.ConnectionAuditOutcome;
import com.knowimran.rivoquery.connection.application.ConnectionAuditPort;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Clock;
import java.util.Objects;

/**
 * Enforces the mandatory audit-ordering invariant for every lifecycle
 * mutation: {@link #recordAttempt} MUST be called, and MUST succeed, before
 * any state/secret/DNS/connector side effect; its failure is surfaced as
 * {@link PreActionAuditWriteFailedException} so the caller aborts before
 * doing anything. {@link #recordTerminal} is called exactly once at the end
 * of every attempt (success or failure); if that write itself fails, the
 * caller is responsible for translating that into
 * {@link IndeterminateLifecycleOutcomeException} when an external effect has
 * already happened (see {@link ConnectionLifecycleService}), or letting it
 * propagate as a plain failure when nothing has happened yet.
 *
 * <p>Every attempt therefore always produces exactly two audit rows: a
 * pre-action row with outcome {@link ConnectionAuditOutcome#INDETERMINATE}
 * (chosen deliberately: the closed {@code ConnectionAuditOutcome} enum has no
 * "attempted"/"started" value, and {@code INDETERMINATE} - "we do not yet
 * know how this concludes" - is the only member that is true of a
 * just-started attempt) and category {@link ConnectionAuditCategory#AUTHORIZATION}
 * (the only thing verified before this write is the caller's own identity;
 * whatever domain-specific category applies is not yet known), and a
 * terminal row with the real outcome/category once the attempt concludes.
 */
final class LifecycleAuditTrail {
    private final ConnectionAuditPort auditPort;
    private final Clock clock;

    LifecycleAuditTrail(ConnectionAuditPort auditPort, Clock clock) {
        this.auditPort = Objects.requireNonNull(auditPort, "auditPort");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void recordAttempt(
            WorkspaceId workspaceId, ActorId actorId, ConnectionId connectionId,
            AuditRequestId requestId, ConnectionAuditAction action) {
        try {
            auditPort.append(new ConnectionAuditPort.Event(
                    workspaceId, actorId, connectionId, requestId, action,
                    ConnectionAuditOutcome.INDETERMINATE, ConnectionAuditCategory.AUTHORIZATION, clock.instant()));
        } catch (RuntimeException auditFailure) {
            throw new PreActionAuditWriteFailedException();
        }
    }

    void recordTerminal(
            WorkspaceId workspaceId, ActorId actorId, ConnectionId connectionId,
            AuditRequestId requestId, ConnectionAuditAction action,
            ConnectionAuditOutcome outcome, ConnectionAuditCategory category) {
        auditPort.append(new ConnectionAuditPort.Event(
                workspaceId, actorId, connectionId, requestId, action, outcome, category, clock.instant()));
    }
}
