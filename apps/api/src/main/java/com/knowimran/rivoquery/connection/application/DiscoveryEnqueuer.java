package com.knowimran.rivoquery.connection.application;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.identity.WorkspaceId;

public interface DiscoveryEnqueuer {
    void enqueue(
            WorkspaceId workspaceId,
            ConnectionId connectionId,
            ConnectionVersion connectionVersion,
            OperationId operationId);
}
