package com.knowimran.rivoquery.catalog;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaFingerprintTest {
    @Test
    void producesStableFingerprintForIdenticalSnapshots() {
        SchemaSnapshot snapshot = new SchemaSnapshot(
            "snap_1",
            new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            new SchemaFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            Instant.parse("2026-01-01T00:00:00Z"),
            1,
            List.of(new SchemaObject(
                new RawQualifiedName("public", "orders"),
                new CanonicalQualifiedName("public", "orders"),
                SchemaObjectType.TABLE,
                "Orders",
                List.of(),
                false,
                false
            ))
        );
        SchemaFingerprint first = SchemaFingerprint.fromSnapshot(snapshot);
        SchemaFingerprint second = SchemaFingerprint.fromSnapshot(snapshot);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void producesDifferentFingerprintsForDifferentObjects() {
        SchemaSnapshot first = snapshot("public", "orders");
        SchemaSnapshot second = snapshot("public", "customers");
        assertThat(SchemaFingerprint.fromSnapshot(first)).isNotEqualTo(SchemaFingerprint.fromSnapshot(second));
    }

    @Test
    void rejectsInvalidHex() {
        assertThatThrownBy(() -> new SchemaFingerprint("not-hex"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private SchemaSnapshot snapshot(String schema, String object) {
        return new SchemaSnapshot(
            "snap_1",
            new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            new SchemaFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            Instant.parse("2026-01-01T00:00:00Z"),
            1,
            List.of(new SchemaObject(
                new RawQualifiedName(schema, object),
                new CanonicalQualifiedName(schema, object),
                SchemaObjectType.TABLE,
                null,
                List.of(),
                false,
                false
            ))
        );
    }
}
