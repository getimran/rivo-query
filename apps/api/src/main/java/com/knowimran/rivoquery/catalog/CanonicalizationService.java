package com.knowimran.rivoquery.catalog;

import com.knowimran.rivoquery.connection.DatabaseKind;
import java.util.Objects;

public final class CanonicalizationService {
    private CanonicalizationService() {
    }

    public static CanonicalQualifiedName canonicalize(DatabaseKind kind, RawQualifiedName raw) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(raw, "raw");
        String schema = CanonicalQualifiedName.normalize(raw.schema());
        String object = CanonicalQualifiedName.normalize(raw.object());
        if (SystemObjectDenylist.isSystemSchema(kind, schema)) {
            throw new IllegalArgumentException("system schema is not permitted: " + schema);
        }
        return new CanonicalQualifiedName(schema, object);
    }
}
