package com.knowimran.rivoquery.catalog;

import com.knowimran.rivoquery.catalog.application.CatalogRepository;
import com.knowimran.rivoquery.catalog.application.SchemaDiscoveryPort;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshService;
import com.knowimran.rivoquery.catalog.application.SchemaSelectorPort;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.identity.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaRefreshServiceTest {
    @Test
    void normalizesAndDeduplicatesSystemObjects() throws SchemaDiscoveryPort.DiscoveryException {
        FakeRepository repo = new FakeRepository();
        SchemaRefreshService service = new SchemaRefreshService(repo, new FakeDiscoveryPort(DatabaseKind.POSTGRESQL));
        SchemaSnapshot snapshot = service.refresh(
            new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            new SchemaDiscoveryPort.DiscoveryPolicy(List.of("public"), List.of(), false, true, 100)
        );
        assertThat(snapshot.objectCount()).isEqualTo(2);
        assertThat(snapshot.objects()).extracting(SchemaObject::canonicalName)
            .containsExactly(
                new CanonicalQualifiedName("public", "customers"),
                new CanonicalQualifiedName("public", "orders")
            );
        assertThat(repo.installed).isTrue();
    }

    @Test
    void canonicalizesNamesAndSorts() throws SchemaDiscoveryPort.DiscoveryException {
        FakeRepository repo = new FakeRepository();
        SchemaRefreshService service = new SchemaRefreshService(repo, new FakeDiscoveryPort(DatabaseKind.POSTGRESQL));
        SchemaSnapshot snapshot = service.refresh(
            new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            new SchemaDiscoveryPort.DiscoveryPolicy(List.of("public"), List.of(), false, true, 100)
        );
        assertThat(snapshot.objects()).hasSize(2);
        assertThat(snapshot.objects().get(0).canonicalName().qualified()).isEqualTo("public.customers");
        assertThat(snapshot.objects().get(1).canonicalName().qualified()).isEqualTo("public.orders");
    }

    @Test
    void rejectsNullWorkspaceId() {
        SchemaRefreshService service = new SchemaRefreshService(new FakeRepository(), new FakeDiscoveryPort(DatabaseKind.POSTGRESQL));
        assertThatThrownBy(() -> service.refresh(null, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"), policy()))
            .isInstanceOf(NullPointerException.class);
    }

    private SchemaDiscoveryPort.DiscoveryPolicy policy() {
        return new SchemaDiscoveryPort.DiscoveryPolicy(List.of("public"), List.of(), false, true, 100);
    }

    private static class FakeRepository implements CatalogRepository {
        boolean installed;

        @Override
        public Optional<SchemaSnapshot> findCurrentSnapshot(WorkspaceId workspaceId, ConnectionId connectionId) {
            return Optional.empty();
        }

        @Override
        public Optional<SchemaSnapshot> findSnapshot(WorkspaceId workspaceId, ConnectionId connectionId, CatalogObjectId snapshotId) {
            return Optional.empty();
        }

        @Override
        public void insert(SchemaSnapshot snapshot) {
        }

        @Override
        public void installCurrentSnapshot(WorkspaceId workspaceId, ConnectionId connectionId, SchemaSnapshot snapshot) {
            installed = true;
        }

        @Override
        public SchemaStatus findStatus(WorkspaceId workspaceId, ConnectionId connectionId) {
            return null;
        }

        @Override
        public SchemaObjectPage findObjects(WorkspaceId workspaceId, ConnectionId connectionId, String cursor, int limit) {
            return null;
        }
    }

    private static class FakeDiscoveryPort implements SchemaDiscoveryPort {
        private final DatabaseKind kind;

        FakeDiscoveryPort(DatabaseKind kind) {
            this.kind = kind;
        }

        @Override
        public DatabaseKind kind() {
            return kind;
        }

        @Override
        public SchemaSnapshot discover(ConnectionId connectionId, DiscoveryPolicy policy) throws DiscoveryException {
            return new SchemaSnapshot(
                "raw_snap",
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                connectionId,
                new SchemaFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                Instant.parse("2026-01-01T00:00:00Z"),
                3,
                List.of(
                    object("pg_catalog", "pg_class"),
                    object("public", "orders"),
                    object("public", "Customers")
                )
            );
        }

        private SchemaObject object(String schema, String object) {
            return new SchemaObject(
                new RawQualifiedName(schema, object),
                new CanonicalQualifiedName(schema, object),
                SchemaObjectType.TABLE,
                null,
                List.of(new SchemaColumn("id", ColumnTypeFamily.NUMERIC, false, null, ColumnClassification.NONE)),
                SystemObjectDenylist.isSystemSchema(DatabaseKind.POSTGRESQL, schema),
                false
            );
        }
    }
}
