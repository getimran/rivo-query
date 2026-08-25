package com.knowimran.rivoquery.connection;

import java.util.Objects;
import java.util.regex.Pattern;

public record OperationId(String value) {
    private static final Pattern CANONICAL = Pattern.compile("^op_[0-9A-HJKMNP-TV-Z]{26}$");

    public OperationId {
        Objects.requireNonNull(value, "value");
        if (!CANONICAL.matcher(value).matches()) {
            throw new IllegalArgumentException("identifier is not canonical");
        }
    }
}
