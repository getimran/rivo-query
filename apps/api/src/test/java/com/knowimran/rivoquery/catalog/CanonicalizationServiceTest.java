package com.knowimran.rivoquery.catalog;

import com.knowimran.rivoquery.connection.DatabaseKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalizationServiceTest {
    @Test
    void canonicalizesAndRejectsSystemSchemas() {
        RawQualifiedName raw = new RawQualifiedName("Sales", "Orders");
        CanonicalQualifiedName canonical = CanonicalizationService.canonicalize(DatabaseKind.POSTGRESQL, raw);
        assertThat(canonical.schema()).isEqualTo("sales");
        assertThat(canonical.object()).isEqualTo("orders");
    }

    @Test
    void rejectsSystemSchemaAfterCanonicalization() {
        RawQualifiedName raw = new RawQualifiedName("pg_catalog", "pg_class");
        assertThatThrownBy(() -> CanonicalizationService.canonicalize(DatabaseKind.POSTGRESQL, raw))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("system schema is not permitted");
    }

    @Test
    void mysqlSystemSchemaRejection() {
        RawQualifiedName raw = new RawQualifiedName("mysql", "user");
        assertThatThrownBy(() -> CanonicalizationService.canonicalize(DatabaseKind.MYSQL, raw))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("system schema is not permitted");
    }
}
