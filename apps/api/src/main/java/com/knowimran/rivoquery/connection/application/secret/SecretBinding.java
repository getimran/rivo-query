package com.knowimran.rivoquery.connection.application.secret;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Objects;

public record SecretBinding(
        WorkspaceId workspaceId, ConnectionId connectionId, SecretPurpose purpose, long version) {
    public SecretBinding {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(purpose, "purpose");
        if (version < 1) {
            throw new IllegalArgumentException("secret version must be positive");
        }
    }

    public void requireConnection(WorkspaceId expectedWorkspace, ConnectionId expectedConnection) {
        if (!workspaceId.equals(expectedWorkspace) || !connectionId.equals(expectedConnection)) {
            throw new SecretAuthorizationException();
        }
    }

    @Override
    public String toString() {
        return "SecretBinding[REDACTED]";
    }
}
