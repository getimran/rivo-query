package com.knowimran.rivoquery.catalog;

import java.util.Objects;
import java.util.regex.Pattern;

public record CatalogObjectId(String value) {
    private static final Pattern CANONICAL = Pattern.compile("^obj_[0-9A-HJKMNP-TV-Z]{26}$");

    public CatalogObjectId {
        Objects.requireNonNull(value, "value");
        if (!CANONICAL.matcher(value).matches()) {
            throw new IllegalArgumentException("identifier is not canonical");
        }
    }
}
