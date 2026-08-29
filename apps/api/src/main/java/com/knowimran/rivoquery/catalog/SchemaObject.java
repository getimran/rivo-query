package com.knowimran.rivoquery.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record SchemaObject(
    RawQualifiedName rawName,
    CanonicalQualifiedName canonicalName,
    SchemaObjectType type,
    String description,
    List<SchemaColumn> columns,
    boolean systemObject,
    boolean deprecated
) {
    private static final int MAX_COLUMNS = 200;

    public SchemaObject {
        Objects.requireNonNull(rawName, "rawName");
        Objects.requireNonNull(canonicalName, "canonicalName");
        Objects.requireNonNull(type, "type");
        if (columns == null) {
            columns = Collections.emptyList();
        }
        List<SchemaColumn> normalized = new ArrayList<>(columns);
        if (normalized.size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("columns exceed maximum of " + MAX_COLUMNS);
        }
        columns = Collections.unmodifiableList(normalized);
        if (description != null && description.isBlank()) {
            description = null;
        }
    }
}
