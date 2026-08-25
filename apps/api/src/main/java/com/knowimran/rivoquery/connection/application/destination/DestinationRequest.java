package com.knowimran.rivoquery.connection.application.destination;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Objects;

public record DestinationRequest(
        WorkspaceId workspaceId,
        ConnectionId connectionId,
        ConnectionVersion connectionVersion,
        DatabaseKind databaseKind,
        ConnectionPurpose purpose,
        StructuredEndpoint endpoint,
        Deadline deadline) {
    public DestinationRequest {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(connectionVersion, "connectionVersion");
        Objects.requireNonNull(databaseKind, "databaseKind");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(deadline, "deadline");
    }
}
