package com.knowimran.rivoquery.connection.application;

import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Optional;

public interface DurableOperationRepository {
    Optional<DurableOperation> findByWorkspaceAndId(WorkspaceId workspaceId, OperationId operationId);

    /**
     * Idempotently persists {@code operation}. A brand-new operation id is
     * always accepted. A write against an existing row is only accepted if
     * it is a legal progression (the stored row is not already terminal,
     * the actor is unchanged, and {@code cancelled} never flips back to
     * {@code false}) or is byte-for-byte identical to the row already
     * stored; any other write throws
     * {@link IllegalDurableOperationTransitionException}.
     */
    void save(DurableOperation operation);
}
