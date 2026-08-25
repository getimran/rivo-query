package com.knowimran.rivoquery.connection.application;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Objects;
import java.util.Optional;

public record DurableOperation(
        OperationId id,
        WorkspaceId workspaceId,
        ActorId actorId,
        Optional<ConnectionId> connectionId,
        OperationAction action,
        OperationState state,
        boolean cancelled) {
    public DurableOperation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(actorId, "actorId");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(state, "state");
    }

    public void requireRunnable() {
        if (cancelled || state == OperationState.SUCCEEDED || state == OperationState.FAILED) {
            throw new IllegalStateException("durable operation is not runnable");
        }
    }
}
