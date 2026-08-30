package com.knowimran.rivoquery.querypolicy;

import com.knowimran.rivoquery.connection.DatabaseKind;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsqlParserSqlParserTest {

    private final SqlParserPort parser = new JsqlParserSqlParser();

    @Test
    void parseAcceptsSimpleSelect() throws Exception {
        SqlParserPort.ParseResult result = parser.parse(
                DatabaseKind.POSTGRESQL,
                "SELECT id, name FROM users WHERE id = 1",
                List.of("public"),
                List.of("public.users"),
                10,
                20,
                5,
                5,
                500
        );
        assertThat(result.isApproved()).isTrue();
        assertThat(result.referencedTables()).contains("users");
        assertThat(result.canonicalSql()).isNotBlank();
        assertThat(result.fingerprint()).isNotBlank();
    }

    @Test
    void parseRejectsInsert() throws Exception {
        SqlParserPort.ParseResult result = parser.parse(
                DatabaseKind.POSTGRESQL,
                "INSERT INTO users (id) VALUES (1)",
                List.of("public"),
                List.of("public.users"),
                10,
                20,
                5,
                5,
                500
        );
        assertThat(result.isApproved()).isFalse();
        assertThat(result.rejectedReasons()).contains("only SELECT statements are accepted");
    }

    @Test
    void parseRejectsSelectInto() throws Exception {
        SqlParserPort.ParseResult result = parser.parse(
                DatabaseKind.POSTGRESQL,
                "SELECT * INTO new_table FROM users",
                List.of("public"),
                List.of("public.users"),
                10,
                20,
                5,
                5,
                500
        );
        assertThat(result.isApproved()).isFalse();
        assertThat(result.rejectedReasons()).contains("SELECT INTO is not allowed");
    }

    @Test
    void parseRejectsRecursiveCte() throws Exception {
        SqlParserPort.ParseResult result = parser.parse(
                DatabaseKind.POSTGRESQL,
                "WITH RECURSIVE cte AS (SELECT 1) SELECT * FROM cte",
                List.of("public"),
                List.of("public.users"),
                10,
                20,
                5,
                5,
                500
        );
        assertThat(result.isApproved()).isFalse();
        assertThat(result.rejectedReasons()).contains("recursive CTEs are not allowed");
    }

    @Test
    void parseRejectsTooManyTables() throws Exception {
        SqlParserPort.ParseResult result = parser.parse(
                DatabaseKind.POSTGRESQL,
                "SELECT * FROM users u JOIN orders o ON u.id = o.user_id JOIN products p ON o.product_id = p.id",
                List.of("public"),
                List.of("public.users", "public.orders", "public.products"),
                2,
                20,
                5,
                5,
                500
        );
        assertThat(result.isApproved()).isFalse();
        assertThat(result.rejectedReasons()).contains("table count 3 exceeds maximum 2");
    }
}
