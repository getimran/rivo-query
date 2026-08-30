package com.knowimran.rivoquery.interfaceapi.schema;

import com.knowimran.rivoquery.catalog.ColumnClassification;
import com.knowimran.rivoquery.catalog.ColumnTypeFamily;
import com.knowimran.rivoquery.catalog.SchemaColumn;
import com.knowimran.rivoquery.catalog.SchemaObject;
import com.knowimran.rivoquery.catalog.SchemaObjectType;
import com.knowimran.rivoquery.catalog.SchemaStatus;
import com.knowimran.rivoquery.interfaceapi.generated.model.SchemaPage;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public final class SchemaDtoMapper {
    private SchemaDtoMapper() {}

    public static com.knowimran.rivoquery.interfaceapi.generated.model.SchemaStatus toStatus(SchemaStatus status) {
        return new com.knowimran.rivoquery.interfaceapi.generated.model.SchemaStatus(
                status.connectionId().value(),
                status.currentSnapshotId(),
                OffsetDateTime.ofInstant(status.discoveredAt(), java.time.ZoneId.systemDefault()),
                status.objectCount(),
                status.stale()
        );
    }

    public static SchemaPage toPage(com.knowimran.rivoquery.catalog.SchemaObjectPage page) {
        List<com.knowimran.rivoquery.interfaceapi.generated.model.SchemaObject> items = new ArrayList<>();
        for (SchemaObject object : page.items()) {
            items.add(toObject(object));
        }
        SchemaPage schemaPage = new SchemaPage(items);
        schemaPage.setNextCursor(page.nextCursor());
        return schemaPage;
    }

    public static com.knowimran.rivoquery.interfaceapi.generated.model.SchemaObject toObject(SchemaObject object) {
        List<com.knowimran.rivoquery.interfaceapi.generated.model.SchemaColumn> columns = new ArrayList<>();
        for (SchemaColumn column : object.columns()) {
            columns.add(new com.knowimran.rivoquery.interfaceapi.generated.model.SchemaColumn()
                    .name(column.name())
                    .type(toTypeFamily(column.type()))
                    .nullable(column.nullable())
                    .description(column.description())
                    .classification(toClassification(column.classification())));
        }
        com.knowimran.rivoquery.interfaceapi.generated.model.SchemaObject dto = new com.knowimran.rivoquery.interfaceapi.generated.model.SchemaObject()
                .rawName(object.rawName().qualified())
                .canonicalName(object.canonicalName().qualified())
                .type(toSchemaObjectType(object.type()))
                .description(object.description())
                .columns(columns)
                .isSystem(object.systemObject());
        dto.setIsDeprecated(object.deprecated());
        return dto;
    }

    private static com.knowimran.rivoquery.interfaceapi.generated.model.SchemaObjectType toSchemaObjectType(SchemaObjectType type) {
        return switch (type) {
            case TABLE -> com.knowimran.rivoquery.interfaceapi.generated.model.SchemaObjectType.TABLE;
            case VIEW -> com.knowimran.rivoquery.interfaceapi.generated.model.SchemaObjectType.VIEW;
            case MATERIALIZED_VIEW -> com.knowimran.rivoquery.interfaceapi.generated.model.SchemaObjectType.MATERIALIZED_VIEW;
            case FUNCTION -> com.knowimran.rivoquery.interfaceapi.generated.model.SchemaObjectType.FUNCTION;
            case PROCEDURE -> com.knowimran.rivoquery.interfaceapi.generated.model.SchemaObjectType.PROCEDURE;
            case SEQUENCE -> com.knowimran.rivoquery.interfaceapi.generated.model.SchemaObjectType.SEQUENCE;
        };
    }

    private static com.knowimran.rivoquery.interfaceapi.generated.model.ColumnTypeFamily toTypeFamily(ColumnTypeFamily type) {
        return switch (type) {
            case STRING -> com.knowimran.rivoquery.interfaceapi.generated.model.ColumnTypeFamily.STRING;
            case NUMERIC -> com.knowimran.rivoquery.interfaceapi.generated.model.ColumnTypeFamily.NUMERIC;
            case TIMESTAMP -> com.knowimran.rivoquery.interfaceapi.generated.model.ColumnTypeFamily.TIMESTAMP;
            case BOOLEAN -> com.knowimran.rivoquery.interfaceapi.generated.model.ColumnTypeFamily.BOOLEAN;
            case BINARY -> com.knowimran.rivoquery.interfaceapi.generated.model.ColumnTypeFamily.BINARY;
            case JSON -> com.knowimran.rivoquery.interfaceapi.generated.model.ColumnTypeFamily.JSON;
            case OTHER -> com.knowimran.rivoquery.interfaceapi.generated.model.ColumnTypeFamily.OTHER;
        };
    }

    private static com.knowimran.rivoquery.interfaceapi.generated.model.ColumnClassification toClassification(ColumnClassification classification) {
        return switch (classification) {
            case NONE -> com.knowimran.rivoquery.interfaceapi.generated.model.ColumnClassification.NONE;
            case PII -> com.knowimran.rivoquery.interfaceapi.generated.model.ColumnClassification.PII;
            case SECRET -> com.knowimran.rivoquery.interfaceapi.generated.model.ColumnClassification.SECRET;
            case DERIVED -> com.knowimran.rivoquery.interfaceapi.generated.model.ColumnClassification.DERIVED;
        };
    }
}
