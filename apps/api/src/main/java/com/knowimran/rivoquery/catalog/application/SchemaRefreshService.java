package com.knowimran.rivoquery.catalog.application;

import com.knowimran.rivoquery.catalog.CanonicalQualifiedName;
import com.knowimran.rivoquery.catalog.CanonicalizationService;
import com.knowimran.rivoquery.catalog.ColumnClassification;
import com.knowimran.rivoquery.catalog.ColumnTypeFamily;
import com.knowimran.rivoquery.catalog.SchemaColumn;
import com.knowimran.rivoquery.catalog.SchemaFingerprint;
import com.knowimran.rivoquery.catalog.SchemaObject;
import com.knowimran.rivoquery.catalog.SchemaObjectType;
import com.knowimran.rivoquery.catalog.SchemaSnapshot;
import com.knowimran.rivoquery.catalog.SystemObjectDenylist;
import com.knowimran.rivoquery.catalog.application.CatalogRepository;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

public class SchemaRefreshService {
    private final CatalogRepository catalogRepository;
    private final SchemaDiscoveryPort discoveryPort;

    public SchemaRefreshService(CatalogRepository catalogRepository, SchemaDiscoveryPort discoveryPort) {
        this.catalogRepository = Objects.requireNonNull(catalogRepository, "catalogRepository");
        this.discoveryPort = Objects.requireNonNull(discoveryPort, "discoveryPort");
    }

    @Transactional
    public SchemaSnapshot refresh(WorkspaceId workspaceId, ConnectionId connectionId, SchemaDiscoveryPort.DiscoveryPolicy policy) throws SchemaDiscoveryPort.DiscoveryException {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(policy, "policy");
        SchemaDiscoveryPort.DiscoveryPolicy safePolicy = new SchemaDiscoveryPort.DiscoveryPolicy(
            policy.allowedSchemas(),
            policy.allowedTables(),
            false,
            true,
            Math.min(policy.maxColumnsPerObject(), 200)
        );
        SchemaSnapshot raw = discoveryPort.discover(connectionId, safePolicy);
        List<SchemaObject> normalized = new ArrayList<>();
        for (SchemaObject object : raw.objects()) {
            if (SystemObjectDenylist.isSystemSchema(discoveryPort.kind(), object.canonicalName().schema())) {
                continue;
            }
            List<SchemaColumn> columns = new ArrayList<>();
            for (SchemaColumn column : object.columns()) {
                ColumnClassification classification = column.classification();
                columns.add(new SchemaColumn(
                    CanonicalQualifiedName.normalize(column.name()),
                    column.type(),
                    column.nullable(),
                    column.description(),
                    classification
                ));
            }
            CanonicalQualifiedName canonical = CanonicalizationService.canonicalize(discoveryPort.kind(), object.rawName());
            normalized.add(new SchemaObject(
                object.rawName(),
                canonical,
                object.type(),
                object.description(),
                columns,
                false,
                object.deprecated()
            ));
        }
        normalized.sort((a, b) -> a.canonicalName().qualified().compareTo(b.canonicalName().qualified()));
        SchemaSnapshot snapshot = new SchemaSnapshot(
            generateSnapshotId(workspaceId, connectionId),
            workspaceId,
            connectionId,
            SchemaFingerprint.fromSnapshot(raw),
            Instant.now(),
            normalized.size(),
            normalized
        );
        catalogRepository.insert(snapshot);
        catalogRepository.installCurrentSnapshot(workspaceId, connectionId, snapshot);
        return snapshot;
    }

    private static String generateSnapshotId(WorkspaceId workspaceId, ConnectionId connectionId) {
        return "snap_" + workspaceId.value() + "_" + connectionId.value();
    }
}
