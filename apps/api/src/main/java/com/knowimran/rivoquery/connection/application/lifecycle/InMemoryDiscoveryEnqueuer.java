package com.knowimran.rivoquery.connection.application.lifecycle;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.connection.application.DiscoveryEnqueuer;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal, no-op-safe {@link DiscoveryEnqueuer} placeholder: it records that
 * discovery was requested (visible via {@link #queued()} for tests/ops
 * visibility) but never executes discovery and never persists the intent
 * durably. Phase 3 owns actual schema discovery and its real queueing
 * infrastructure (a durable queue/worker); this class exists only so P2-100's
 * "create" saga has something safe to call today. It is explicitly NOT a
 * durable queue: entries are lost on restart, and nothing ever drains them.
 */
public final class InMemoryDiscoveryEnqueuer implements DiscoveryEnqueuer {
    private final List<QueuedDiscovery> queued = new CopyOnWriteArrayList<>();

    @Override
    public void enqueue(
            WorkspaceId workspaceId, ConnectionId connectionId, ConnectionVersion connectionVersion,
            OperationId operationId) {
        queued.add(new QueuedDiscovery(
                Objects.requireNonNull(workspaceId, "workspaceId"),
                Objects.requireNonNull(connectionId, "connectionId"),
                Objects.requireNonNull(connectionVersion, "connectionVersion"),
                Objects.requireNonNull(operationId, "operationId")));
    }

    public List<QueuedDiscovery> queued() {
        return List.copyOf(queued);
    }

    public record QueuedDiscovery(
            WorkspaceId workspaceId, ConnectionId connectionId, ConnectionVersion connectionVersion,
            OperationId operationId) {}
}
