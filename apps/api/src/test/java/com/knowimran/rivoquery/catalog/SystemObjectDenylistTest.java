package com.knowimran.rivoquery.catalog;

import com.knowimran.rivoquery.connection.DatabaseKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemObjectDenylistTest {
    @Test
    void postgresqlDenylistsSystemSchemas() {
        assertThat(SystemObjectDenylist.isSystemSchema(DatabaseKind.POSTGRESQL, "pg_catalog")).isTrue();
        assertThat(SystemObjectDenylist.isSystemSchema(DatabaseKind.POSTGRESQL, "information_schema")).isTrue();
        assertThat(SystemObjectDenylist.isSystemSchema(DatabaseKind.POSTGRESQL, "pg_toast")).isTrue();
        assertThat(SystemObjectDenylist.isSystemSchema(DatabaseKind.POSTGRESQL, "sales")).isFalse();
    }

    @Test
    void mysqlDenylistsSystemSchemas() {
        assertThat(SystemObjectDenylist.isSystemSchema(DatabaseKind.MYSQL, "information_schema")).isTrue();
        assertThat(SystemObjectDenylist.isSystemSchema(DatabaseKind.MYSQL, "mysql")).isTrue();
        assertThat(SystemObjectDenylist.isSystemSchema(DatabaseKind.MYSQL, "performance_schema")).isTrue();
        assertThat(SystemObjectDenylist.isSystemSchema(DatabaseKind.MYSQL, "sys")).isTrue();
        assertThat(SystemObjectDenylist.isSystemSchema(DatabaseKind.MYSQL, "sales")).isFalse();
    }

    @Test
    void caseSensitiveComparison() {
        assertThat(SystemObjectDenylist.isSystemSchema(DatabaseKind.POSTGRESQL, "PG_CATALOG")).isFalse();
        assertThat(SystemObjectDenylist.isSystemSchema(DatabaseKind.MYSQL, "MYSQL")).isFalse();
    }
}
