package com.knowimran.rivoquery.catalog;

import java.util.Objects;
import java.util.regex.Pattern;

public record RawQualifiedName(String schema, String object) {
    private static final Pattern SCHEMA = Pattern.compile("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*$");
    private static final Pattern OBJECT = Pattern.compile("^[A-Za-z0-9_]+$");

    public RawQualifiedName {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(object, "object");
        if (schema.isBlank() || object.isBlank()) {
            throw new IllegalArgumentException("schema and object must not be blank");
        }
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("schema is not a valid raw identifier");
        }
        if (!OBJECT.matcher(object).matches()) {
            throw new IllegalArgumentException("object is not a valid raw identifier");
        }
    }

    public String qualified() {
        return schema + "." + object;
    }
}
