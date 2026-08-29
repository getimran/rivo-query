package com.knowimran.rivoquery.catalog;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record CanonicalQualifiedName(String schema, String object) {
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9_]{1,128}$");

    public CanonicalQualifiedName {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(object, "object");
        String normalizedSchema = normalize(schema);
        String normalizedObject = normalize(object);
        if (normalizedSchema.isBlank() || normalizedObject.isBlank()) {
            throw new IllegalArgumentException("schema and object must not be blank after normalization");
        }
        if (!SAFE.matcher(normalizedSchema).matches()) {
            throw new IllegalArgumentException("schema is not a valid canonical identifier");
        }
        if (!SAFE.matcher(normalizedObject).matches()) {
            throw new IllegalArgumentException("object is not a valid canonical identifier");
        }
        schema = normalizedSchema;
        object = normalizedObject;
    }

    public static String normalize(String value) {
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.replaceAll("\\s+", " ");
    }

    public String qualified() {
        return schema + "." + object;
    }
}
