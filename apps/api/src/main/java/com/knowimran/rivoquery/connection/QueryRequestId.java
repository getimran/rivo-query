package com.knowimran.rivoquery.connection;

import java.util.Objects;
import java.util.regex.Pattern;

public record QueryRequestId(String value) {
    private static final Pattern CANONICAL = Pattern.compile("^qry_[0-9A-HJKMNP-TV-Z]{26}$");

    public QueryRequestId {
        Objects.requireNonNull(value, "value");
        if (!CANONICAL.matcher(value).matches()) {
            throw new IllegalArgumentException("identifier is not canonical");
        }
    }
}
