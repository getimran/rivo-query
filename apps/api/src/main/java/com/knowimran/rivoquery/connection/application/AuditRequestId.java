package com.knowimran.rivoquery.connection.application;

import java.util.Objects;
import java.util.regex.Pattern;

public record AuditRequestId(String value) {
    private static final Pattern CANONICAL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    public AuditRequestId {
        Objects.requireNonNull(value, "value");
        if (!CANONICAL.matcher(value).matches()) {
            throw new IllegalArgumentException("request identifier is invalid");
        }
    }
}
