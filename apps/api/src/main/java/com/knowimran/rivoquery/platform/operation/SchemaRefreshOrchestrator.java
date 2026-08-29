package com.knowimran.rivoquery.platform.operation;

import com.knowimran.rivoquery.catalog.SchemaRefreshOperation;
import com.knowimran.rivoquery.catalog.application.CatalogRepository;
import com.knowimran.rivoquery.catalog.application.SchemaDiscoveryPort;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshConnectionFactory;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshOperationRepository;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshService;
import com.knowimran.rivoquery.catalog.application.SchemaDiscoveryPort.DiscoveryException;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public class SchemaRefreshOrchestrator {
    private final SchemaRefreshWorker worker;
    private final SchemaRefreshService refreshService;
    private final CatalogRepository catalogRepository;
    private final SchemaRefreshOperationRepository operationRepository;
    private final SchemaRefreshConnectionFactory connectionFactory;
    private final Clock clock;

    public SchemaRefreshOrchestrator(
            SchemaRefreshWorker worker,
            SchemaRefreshService refreshService,
            CatalogRepository catalogRepository,
            SchemaRefreshOperationRepository operationRepository,
            SchemaRefreshConnectionFactory connectionFactory,
            Clock clock) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.refreshService = Objects.requireNonNull(refreshService, "refreshService");
        this.catalogRepository = Objects.requireNonNull(catalogRepository, "catalogRepository");
        this.operationRepository = Objects.requireNonNull(operationRepository, "operationRepository");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void refreshSchema(ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId) throws DiscoveryException {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        String operationId = "op_" + workspaceId.value() + "_" + connectionId.value() + "_" + Instant.now(clock).toEpochMilli();
        operationRepository.insert(new SchemaRefreshOperation(
                operationId,
                workspaceId,
                connectionId,
                "PENDING",
                null,
                null,
                null,
                0,
                1,
                3,
                5000,
                Instant.now(clock),
                Instant.now(clock)
        ));
        if (!worker.claim(operationId)) {
            throw new DiscoveryException("failed to claim refresh operation");
        }
        try (SchemaRefreshConnectionFactory.Wrapped wrapped = connectionFactory.openWrapped(actor, workspaceId, connectionId)) {
            refreshService.refresh(wrapped.connection(), workspaceId, connectionId,
                    new SchemaDiscoveryPort.DiscoveryPolicy(
                            java.util.List.of("public"),
                            java.util.List.of(),
                            false,
                            true,
                            200
                    ));
            worker.complete(operationId);
        } catch (Exception e) {
            worker.fail(operationId);
            throw new DiscoveryException("refresh failed: " + e.getMessage(), e);
        }
    }
}
