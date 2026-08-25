package com.knowimran.rivoquery.connection.application.secret;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.WorkspaceId;

public interface SecretOperations {
    StagedSecretReference stage(
            ActorContext actor,
            ConnectionId connectionId,
            SecretBinding binding,
            DestructibleSecretMaterial material,
            OperationId operationId,
            Deadline deadline);

    void consume(
            ActorContext actor,
            ConnectionId connectionId,
            SecretUsePurpose usePurpose,
            Deadline deadline,
            SecretMaterialConsumer consumer);

    void consumeForOperation(
            WorkspaceId workspaceId,
            OperationId operationId,
            ConnectionId connectionId,
            BoundSecretReference secret,
            SecretUsePurpose usePurpose,
            Deadline deadline,
            SecretMaterialConsumer consumer);

    void revokeForOperation(
            WorkspaceId workspaceId,
            OperationId operationId,
            ConnectionId connectionId,
            BoundSecretReference secret,
            Deadline deadline);
}
