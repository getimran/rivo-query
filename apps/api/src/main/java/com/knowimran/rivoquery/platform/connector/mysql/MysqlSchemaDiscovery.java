package com.knowimran.rivoquery.platform.connector.mysql;

import com.knowimran.rivoquery.catalog.CanonicalQualifiedName;
import com.knowimran.rivoquery.catalog.CanonicalizationService;
import com.knowimran.rivoquery.catalog.ColumnClassification;
import com.knowimran.rivoquery.catalog.ColumnTypeFamily;
import com.knowimran.rivoquery.catalog.RawQualifiedName;
import com.knowimran.rivoquery.catalog.SchemaColumn;
import com.knowimran.rivoquery.catalog.SchemaFingerprint;
import com.knowimran.rivoquery.catalog.SchemaObject;
import com.knowimran.rivoquery.catalog.SchemaObjectType;
import com.knowimran.rivoquery.catalog.SchemaSnapshot;
import com.knowimran.rivoquery.catalog.SystemObjectDenylist;
import com.knowimran.rivoquery.catalog.application.SchemaDiscoveryPort;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MysqlSchemaDiscovery implements SchemaDiscoveryPort {

    @Override
    public DatabaseKind kind() {
        return DatabaseKind.MYSQL;
    }

    @Override
    public SchemaSnapshot discover(Connection connection, WorkspaceId workspaceId, ConnectionId connectionId, DiscoveryPolicy policy) throws DiscoveryException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(policy, "policy");
        Set<String> allowedSchemas = new HashSet<>(policy.allowedSchemas());
        List<SchemaObject> objects = new ArrayList<>();
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String[] tableTypes = {"TABLE", "VIEW"};
            try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, null, tableTypes)) {
                while (tables.next()) {
                    String schema = tables.getString("TABLE_SCHEM");
                    String table = tables.getString("TABLE_NAME");
                    String tableType = tables.getString("TABLE_TYPE");
                    if (!allowedSchemas.contains(schema)) {
                        continue;
                    }
                    String canonicalSchema = CanonicalQualifiedName.normalize(schema);
                    if (SystemObjectDenylist.isSystemSchema(DatabaseKind.MYSQL, canonicalSchema)) {
                        continue;
                    }
                    SchemaObjectType type = switch (tableType) {
                        case "TABLE" -> SchemaObjectType.TABLE;
                        case "VIEW" -> SchemaObjectType.VIEW;
                        default -> SchemaObjectType.TABLE;
                    };
                    List<SchemaColumn> columns = new ArrayList<>();
                    try (ResultSet columnsRs = metaData.getColumns(connection.getCatalog(), schema, table, null)) {
                        int count = 0;
                        while (columnsRs.next() && count < policy.maxColumnsPerObject()) {
                            String columnName = columnsRs.getString("COLUMN_NAME");
                            int dataType = columnsRs.getInt("DATA_TYPE");
                            boolean nullable = columnsRs.getBoolean("NULLABLE");
                            String remarks = columnsRs.getString("REMARKS");
                            columns.add(new SchemaColumn(
                                    CanonicalQualifiedName.normalize(columnName),
                                    mapDataType(dataType),
                                    nullable,
                                    remarks,
                                    ColumnClassification.NONE
                            ));
                            count++;
                        }
                    }
                    CanonicalQualifiedName canonical = CanonicalizationService.canonicalize(
                            DatabaseKind.MYSQL,
                            new RawQualifiedName(schema, table));
                    objects.add(new SchemaObject(
                            new RawQualifiedName(schema, table),
                            canonical,
                            type,
                            null,
                            columns,
                            false,
                            false
                    ));
                }
            }
            objects.sort((a, b) -> a.canonicalName().qualified().compareTo(b.canonicalName().qualified()));
            return new SchemaSnapshot(
                    "discovered",
                    workspaceId,
                    connectionId,
                    new SchemaFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                    Instant.now(),
                    objects.size(),
                    objects
            );
        } catch (SQLException e) {
            throw new DiscoveryException("MySQL schema discovery failed: " + e.getMessage(), e);
        }
    }

    private static ColumnTypeFamily mapDataType(int dataType) {
        return switch (dataType) {
            case java.sql.Types.CHAR, java.sql.Types.VARCHAR, java.sql.Types.LONGVARCHAR, java.sql.Types.CLOB -> ColumnTypeFamily.STRING;
            case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL, java.sql.Types.BIT, java.sql.Types.BOOLEAN -> ColumnTypeFamily.NUMERIC;
            case java.sql.Types.TINYINT, java.sql.Types.SMALLINT, java.sql.Types.INTEGER, java.sql.Types.BIGINT -> ColumnTypeFamily.NUMERIC;
            case java.sql.Types.REAL, java.sql.Types.FLOAT, java.sql.Types.DOUBLE -> ColumnTypeFamily.NUMERIC;
            case java.sql.Types.DATE, java.sql.Types.TIME, java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> ColumnTypeFamily.TIMESTAMP;
            case java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.LONGVARBINARY, java.sql.Types.BLOB -> ColumnTypeFamily.BINARY;
            case java.sql.Types.OTHER -> ColumnTypeFamily.OTHER;
            default -> ColumnTypeFamily.OTHER;
        };
    }
}
