package com.knowimran.rivoquery.connection.application.lifecycle;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.OperationId;

/**
 * Mints fresh, unpredictable canonical identifiers for newly created
 * connections and durable/secret operations. No implementation of either
 * {@link ConnectionId} or {@link OperationId} previously existed anywhere in
 * the codebase (every prior use only parsed an already-known id), so the
 * P2-100 lifecycle saga - the first code that ever creates a brand-new
 * connection or operation - is what first needs one.
 */
public interface CanonicalIdGenerator {
    ConnectionId newConnectionId();

    OperationId newOperationId();

    String newSnapshotId();

    com.knowimran.rivoquery.connection.QueryRequestId newQueryRequestId();
}
