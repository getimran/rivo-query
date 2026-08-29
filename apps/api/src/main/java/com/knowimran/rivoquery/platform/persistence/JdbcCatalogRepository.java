package com.knowimran.rivoquery.platform.persistence;

import com.knowimran.rivoquery.catalog.CanonicalQualifiedName;
import com.knowimran.rivoquery.catalog.CatalogObjectId;
import com.knowimran.rivoquery.catalog.ColumnClassification;
import com.knowimran.rivoquery.catalog.ColumnTypeFamily;
import com.knowimran.rivoquery.catalog.RawQualifiedName;
import com.knowimran.rivoquery.catalog.SchemaColumn;
import com.knowimran.rivoquery.catalog.SchemaFingerprint;
import com.knowimran.rivoquery.catalog.SchemaObject;
import com.knowimran.rivoquery.catalog.SchemaObjectPage;
import com.knowimran.rivoquery.catalog.SchemaObjectType;
import com.knowimran.rivoquery.catalog.SchemaSnapshot;
import com.knowimran.rivoquery.catalog.SchemaStatus;
import com.knowimran.rivoquery.catalog.application.CatalogRepository;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCatalogRepository implements CatalogRepository {

    private static final String FIND_CURRENT_SNAPSHOT = """
            SELECT s.snapshot_id, s.workspace_id, s.connection_id, s.fingerprint,
                   s.discovered_at, s.object_count
            FROM schema_snapshots s
            JOIN current_schema_snapshot c
              ON c.snapshot_id = s.snapshot_id
            WHERE c.workspace_id = ? AND c.connection_id = ?
            """;

    private static final String FIND_SNAPSHOT = """
            SELECT snapshot_id, workspace_id, connection_id, fingerprint,
                   discovered_at, object_count
            FROM schema_snapshots
            WHERE snapshot_id = ? AND workspace_id = ? AND connection_id = ?
            """;

    private static final String INSERT_SNAPSHOT = """
            INSERT INTO schema_snapshots
                (snapshot_id, workspace_id, connection_id, fingerprint,
                 discovered_at, object_count)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_OBJECT = """
            INSERT INTO schema_objects
                (snapshot_id, object_index, raw_schema, raw_object,
                 canonical_schema, canonical_object, object_type, description,
                 system_object, deprecated)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_COLUMN = """
            INSERT INTO schema_columns
                (snapshot_id, object_index, column_index, canonical_schema,
                 canonical_object, type_family, nullable, description,
                 classification)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSTALL_CURRENT_SNAPSHOT = """
            INSERT INTO current_schema_snapshot (workspace_id, connection_id, snapshot_id)
            VALUES (?, ?, ?)
            ON CONFLICT (workspace_id, connection_id) DO UPDATE SET
                snapshot_id = EXCLUDED.snapshot_id,
                updated_at = now()
            """;

    private static final String FIND_STATUS = """
            SELECT s.snapshot_id, s.discovered_at, s.object_count
            FROM schema_snapshots s
            JOIN current_schema_snapshot c
              ON c.snapshot_id = s.snapshot_id
            WHERE c.workspace_id = ? AND c.connection_id = ?
            """;

    private static final String FIND_OBJECTS = """
            SELECT o.snapshot_id, o.object_index, o.raw_schema, o.raw_object,
                   o.canonical_schema, o.canonical_object, o.object_type,
                   o.description, o.system_object, o.deprecated
            FROM schema_objects o
            JOIN schema_snapshots s ON s.snapshot_id = o.snapshot_id
            JOIN current_schema_snapshot c ON c.snapshot_id = s.snapshot_id
            WHERE c.workspace_id = ? AND c.connection_id = ?
              AND o.object_index > COALESCE(?, 0)
            ORDER BY o.object_index
            LIMIT ?
            """;

    private static final String FIND_COLUMNS = """
            SELECT object_index, column_index, canonical_schema, canonical_object,
                   type_family, nullable, description, classification
            FROM schema_columns
            WHERE snapshot_id = ?
            ORDER BY object_index, column_index
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcCatalogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public Optional<SchemaSnapshot> findCurrentSnapshot(WorkspaceId workspaceId, ConnectionId connectionId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        List<SchemaSnapshot> rows = jdbcTemplate.query(
                FIND_CURRENT_SNAPSHOT,
                (resultSet, rowNumber) -> mapSnapshot(resultSet),
                workspaceId.value(), connectionId.value());
        return rows.stream().findFirst();
    }

    @Override
    public Optional<SchemaSnapshot> findSnapshot(WorkspaceId workspaceId, ConnectionId connectionId, CatalogObjectId snapshotId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        List<SchemaSnapshot> rows = jdbcTemplate.query(
                FIND_SNAPSHOT,
                (resultSet, rowNumber) -> mapSnapshot(resultSet),
                snapshotId.value(), workspaceId.value(), connectionId.value());
        return rows.stream().findFirst();
    }

    @Override
    public void insert(SchemaSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        jdbcTemplate.update(INSERT_SNAPSHOT, ps -> {
            ps.setString(1, snapshot.snapshotId());
            ps.setString(2, snapshot.workspaceId().value());
            ps.setString(3, snapshot.connectionId().value());
            ps.setString(4, snapshot.fingerprint().value());
            ps.setTimestamp(5, Timestamp.from(snapshot.discoveredAt()));
            ps.setInt(6, snapshot.objectCount());
        });
        insertObjects(snapshot.snapshotId(), snapshot.objects());
        insertColumns(snapshot.snapshotId(), snapshot.objects());
    }

    @Override
    public void installCurrentSnapshot(WorkspaceId workspaceId, ConnectionId connectionId, SchemaSnapshot snapshot) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(snapshot, "snapshot");
        jdbcTemplate.update(INSTALL_CURRENT_SNAPSHOT, workspaceId.value(), connectionId.value(), snapshot.snapshotId());
    }

    @Override
    public SchemaStatus findStatus(WorkspaceId workspaceId, ConnectionId connectionId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        List<SchemaStatus> rows = jdbcTemplate.query(
                FIND_STATUS,
                (resultSet, rowNumber) -> new SchemaStatus(
                        connectionId,
                        resultSet.getString("snapshot_id"),
                        resultSet.getTimestamp("discovered_at").toInstant(),
                        resultSet.getInt("object_count"),
                        false
                ),
                workspaceId.value(), connectionId.value());
        return rows.stream().findFirst().orElse(null);
    }

    @Override
    public SchemaObjectPage findObjects(WorkspaceId workspaceId, ConnectionId connectionId, String cursor, int limit) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        int afterIndex = cursor != null && !cursor.isBlank() ? Integer.parseInt(cursor) : 0;
        List<SchemaObject> rawObjects = new ArrayList<>();
        List<Integer> objectIndices = new ArrayList<>();
        final String[] snapshotIdHolder = {null};
        final String[] nextCursorHolder = {null};
        jdbcTemplate.query(
                FIND_OBJECTS,
                (ResultSet rs) -> {
                    int count = 0;
                    while (rs.next()) {
                        if (count >= limit + 1) {
                            break;
                        }
                        snapshotIdHolder[0] = rs.getString("snapshot_id");
                        objectIndices.add(rs.getInt("object_index"));
                        rawObjects.add(mapObject(rs));
                        nextCursorHolder[0] = String.valueOf(rs.getInt("object_index"));
                        count++;
                    }
                },
                workspaceId.value(), connectionId.value(), afterIndex, limit + 1);
        String snapshotId = snapshotIdHolder[0];
        if (snapshotId == null || rawObjects.isEmpty()) {
            return new SchemaObjectPage(List.of(), null);
        }
        Map<Integer, List<SchemaColumn>> columnsByIndex = loadColumns(snapshotId);
        List<SchemaObject> resultObjects = new ArrayList<>();
        for (int i = 0; i < rawObjects.size(); i++) {
            SchemaObject object = rawObjects.get(i);
            List<SchemaColumn> columns = columnsByIndex.getOrDefault(objectIndices.get(i), List.of());
            resultObjects.add(new SchemaObject(
                    object.rawName(),
                    object.canonicalName(),
                    object.type(),
                    object.description(),
                    columns,
                    object.systemObject(),
                    object.deprecated()
            ));
        }
        if (resultObjects.size() > limit) {
            resultObjects = new ArrayList<>(resultObjects.subList(0, limit));
        } else {
            nextCursorHolder[0] = null;
        }
        return new SchemaObjectPage(resultObjects, nextCursorHolder[0]);
    }

    private SchemaSnapshot mapSnapshot(ResultSet resultSet) throws SQLException {
        String snapshotId = resultSet.getString("snapshot_id");
        WorkspaceId workspaceId = new WorkspaceId(resultSet.getString("workspace_id"));
        ConnectionId connectionId = new ConnectionId(resultSet.getString("connection_id"));
        String fingerprint = resultSet.getString("fingerprint");
        Instant discoveredAt = resultSet.getTimestamp("discovered_at").toInstant();
        int objectCount = resultSet.getInt("object_count");
        return new SchemaSnapshot(
                snapshotId,
                workspaceId,
                connectionId,
                new SchemaFingerprint(fingerprint),
                discoveredAt,
                objectCount,
                List.of()
        );
    }

    private SchemaObject mapObject(ResultSet resultSet) throws SQLException {
        return new SchemaObject(
                new RawQualifiedName(
                        resultSet.getString("raw_schema"),
                        resultSet.getString("raw_object")),
                new CanonicalQualifiedName(
                        resultSet.getString("canonical_schema"),
                        resultSet.getString("canonical_object")),
                SchemaObjectType.valueOf(resultSet.getString("object_type")),
                resultSet.getString("description"),
                List.of(),
                resultSet.getBoolean("system_object"),
                resultSet.getBoolean("deprecated")
        );
    }

    private void insertObjects(String snapshotId, List<SchemaObject> objects) {
        for (int objectIndex = 0; objectIndex < objects.size(); objectIndex++) {
            SchemaObject object = objects.get(objectIndex);
            jdbcTemplate.update(INSERT_OBJECT, ps -> {
                ps.setString(1, snapshotId);
                ps.setInt(2, objectIndex);
                ps.setString(3, object.rawName().schema());
                ps.setString(4, object.rawName().object());
                ps.setString(5, object.canonicalName().schema());
                ps.setString(6, object.canonicalName().object());
                ps.setString(7, object.type().name());
                ps.setString(8, object.description());
                ps.setBoolean(9, object.systemObject());
                ps.setBoolean(10, object.deprecated());
            });
        }
    }

    private void insertColumns(String snapshotId, List<SchemaObject> objects) {
        for (int objectIndex = 0; objectIndex < objects.size(); objectIndex++) {
            SchemaObject object = objects.get(objectIndex);
            List<SchemaColumn> columns = object.columns();
            for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                SchemaColumn column = columns.get(columnIndex);
                jdbcTemplate.update(INSERT_COLUMN, ps -> {
                    ps.setString(1, snapshotId);
                    ps.setInt(2, objectIndex);
                    ps.setInt(3, columnIndex);
                    ps.setString(4, column.name().schema());
                    ps.setString(5, column.name().object());
                    ps.setString(6, column.type().name());
                    ps.setBoolean(7, column.nullable());
                    ps.setString(8, column.description());
                    ps.setString(9, column.classification().name());
                });
            }
        }
    }

    private Map<Integer, List<SchemaColumn>> loadColumns(String snapshotId) {
        Map<Integer, List<SchemaColumn>> columnsByIndex = new LinkedHashMap<>();
        jdbcTemplate.query(FIND_COLUMNS, (ResultSet rs) -> {
            while (rs.next()) {
                int objectIndex = rs.getInt("object_index");
                String classification = rs.getString("classification");
                ColumnClassification classificationEnum = classification != null
                        ? ColumnClassification.valueOf(classification)
                        : ColumnClassification.NONE;
                SchemaColumn column = new SchemaColumn(
                        new CanonicalQualifiedName(
                                rs.getString("canonical_schema"),
                                rs.getString("canonical_object")),
                        ColumnTypeFamily.valueOf(rs.getString("type_family")),
                        rs.getBoolean("nullable"),
                        rs.getString("description"),
                        classificationEnum
                );
                columnsByIndex.computeIfAbsent(objectIndex, k -> new ArrayList<>()).add(column);
            }
        }, snapshotId);
        return columnsByIndex;
    }
}
