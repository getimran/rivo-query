package com.knowimran.rivoquery.platform.persistence;

import com.knowimran.rivoquery.catalog.CatalogObjectId;
import com.knowimran.rivoquery.catalog.SchemaFingerprint;
import com.knowimran.rivoquery.catalog.SchemaSnapshot;
import com.knowimran.rivoquery.catalog.application.CatalogRepository;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcCatalogRepositoryTest {

    @Test
    void snapshotRecordRejectsInvalidFingerprint() {
        assertThatThrownBy(() -> new SchemaFingerprint("not-hex"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotRecordRequiresObjectCountToMatchObjects() {
        SchemaSnapshot snapshot = new SchemaSnapshot(
            "snap_1",
            new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            new SchemaFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            Instant.parse("2026-01-01T00:00:00Z"),
            0,
            List.of()
        );
        assertThat(snapshot.objectCount()).isEqualTo(0);
    }

    @Test
    void constructorRejectsNullJdbcTemplate() {
        assertThatThrownBy(() -> new JdbcCatalogRepository(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void findCurrentSnapshotRejectsNullArguments() {
        JdbcCatalogRepository repository = new JdbcCatalogRepository(new JdbcTemplate(null));
        assertThatThrownBy(() -> repository.findCurrentSnapshot(null, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX")))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.findCurrentSnapshot(new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"), null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void findSnapshotRejectsNullArguments() {
        JdbcCatalogRepository repository = new JdbcCatalogRepository(new JdbcTemplate(null));
        assertThatThrownBy(() -> repository.findSnapshot(null, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"), new CatalogObjectId("obj_1")))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void insertRejectsNullSnapshot() {
        JdbcCatalogRepository repository = new JdbcCatalogRepository(new JdbcTemplate(null));
        assertThatThrownBy(() -> repository.insert(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void installCurrentSnapshotRejectsNullArguments() {
        JdbcCatalogRepository repository = new JdbcCatalogRepository(new JdbcTemplate(null));
        assertThatThrownBy(() -> repository.installCurrentSnapshot(null, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"), new SchemaSnapshot(
            "snap_1",
            new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            new SchemaFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            Instant.now(),
            0,
            List.of()
        )))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void findStatusRejectsNullArguments() {
        JdbcCatalogRepository repository = new JdbcCatalogRepository(new JdbcTemplate(null));
        assertThatThrownBy(() -> repository.findStatus(null, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX")))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void findObjectsRejectsNullArguments() {
        JdbcCatalogRepository repository = new JdbcCatalogRepository(new JdbcTemplate(null));
        assertThatThrownBy(() -> repository.findObjects(null, new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"), "0", 10))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.findObjects(new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"), null, "0", 10))
            .isInstanceOf(NullPointerException.class);
    }
}
