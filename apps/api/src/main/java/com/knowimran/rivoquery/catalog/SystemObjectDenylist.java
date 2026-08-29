package com.knowimran.rivoquery.catalog;

import com.knowimran.rivoquery.connection.DatabaseKind;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SystemObjectDenylist {
    private SystemObjectDenylist() {
    }

    private static final Set<String> POSTGRESQL_SYSTEM_SCHEMAS = Set.of(
        "pg_catalog",
        "information_schema",
        "pg_toast"
    );

    private static final Set<String> MYSQL_SYSTEM_SCHEMAS = Set.of(
        "information_schema",
        "mysql",
        "performance_schema",
        "sys"
    );

    public static boolean isSystemSchema(DatabaseKind kind, String canonicalSchema) {
        Set<String> denylist = kind == DatabaseKind.MYSQL ? MYSQL_SYSTEM_SCHEMAS : POSTGRESQL_SYSTEM_SCHEMAS;
        return denylist.contains(canonicalSchema);
    }

    public static List<String> systemSchemas(DatabaseKind kind) {
        if (kind == DatabaseKind.MYSQL) {
            return List.copyOf(MYSQL_SYSTEM_SCHEMAS);
        }
        return List.copyOf(POSTGRESQL_SYSTEM_SCHEMAS);
    }
}
