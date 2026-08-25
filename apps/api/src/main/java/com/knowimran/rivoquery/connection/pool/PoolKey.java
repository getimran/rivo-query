package com.knowimran.rivoquery.connection.pool;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Objects;

public record PoolKey(
        WorkspaceId workspaceId,
        ConnectionId connectionId,
        ConnectionVersion connectionVersion,
        long secretVersion,
        String destinationPolicyVersion) {
    public PoolKey {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(connectionVersion, "connectionVersion");
        Objects.requireNonNull(destinationPolicyVersion, "destinationPolicyVersion");
        if (secretVersion < 1 || destinationPolicyVersion.isBlank()) {
            throw new IllegalArgumentException("pool key is invalid");
        }
    }
}
