package com.knowimran.rivoquery.connection.application.secret;

import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Objects;
import java.util.Optional;

public interface DurableSecretAuthorization {
    Optional<AuthorizedOperation> findByWorkspaceAndId(
            WorkspaceId workspaceId, OperationId operationId);

    record AuthorizedOperation(
            WorkspaceId workspaceId,
            OperationId operationId,
            Optional<ActorId> actorId,
            SecretOperationKind kind,
            SecretBinding binding,
            Optional<SecretReference> reference,
            Optional<SecretUsePurpose> usePurpose,
            boolean cancelled) {
        public AuthorizedOperation {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(operationId, "operationId");
            actorId = Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(binding, "binding");
            reference = Objects.requireNonNull(reference, "reference");
            usePurpose = Objects.requireNonNull(usePurpose, "usePurpose");
        }
    }
}
