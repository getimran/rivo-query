package com.knowimran.rivoquery.catalog;

import java.util.Objects;
import java.util.regex.Pattern;

public record SchemaColumn(
    String name,
    ColumnTypeFamily type,
    boolean nullable,
    String description,
    ColumnClassification classification
) {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,256}$");

    public SchemaColumn {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(classification, "classification");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("column name is not a valid identifier");
        }
        if (description != null && description.isBlank()) {
            description = null;
        }
    }
}
