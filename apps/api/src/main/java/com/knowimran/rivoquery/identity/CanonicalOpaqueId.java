package com.knowimran.rivoquery.identity;

import java.util.Objects;
import java.util.regex.Pattern;

final class CanonicalOpaqueId {
    private CanonicalOpaqueId() {}

    static String require(String value, String prefix) {
        Objects.requireNonNull(value, "value");
        var pattern = Pattern.compile("^" + Pattern.quote(prefix) + "_[0-9A-HJKMNP-TV-Z]{26}$");
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("identifier is not canonical");
        }
        return value;
    }
}
