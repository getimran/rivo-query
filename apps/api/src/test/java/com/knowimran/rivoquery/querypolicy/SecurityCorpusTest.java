package com.knowimran.rivoquery.querypolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.knowimran.rivoquery.connection.DatabaseKind;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SecurityCorpusTest {

    private final SqlParserPort parser = new JsqlParserSqlParser();

    static final List<String> ALLOWED_SCHEMAS = List.of("public");
    static final List<String> ALLOWED_TABLES = List.of("public.users", "public.orders");
    static final int MAX_TABLES = 10, MAX_COLUMNS = 100, MAX_JOINS = 10, MAX_CTE = 5;
    static final long DEADLINE = 500;

    boolean isRejected(String sql) {
        try {
            SqlParserPort.ParseResult result = parser.parse(DatabaseKind.POSTGRESQL, sql, ALLOWED_SCHEMAS, ALLOWED_TABLES,
                    MAX_TABLES, MAX_COLUMNS, MAX_JOINS, MAX_CTE, DEADLINE);
            return !result.isApproved();
        } catch (SqlParserPort.ParseException | SqlParserPort.TimeoutException e) {
            return true;
        }
    }

    @ParameterizedTest
    @CsvSource({
            "UPDATE public.users SET name='x'",
            "DELETE FROM public.users WHERE id=1",
            "INSERT INTO public.users (id) VALUES (1)",
            "TRUNCATE public.users",
            "ALTER TABLE public.users ADD COLUMN hack TEXT",
            "DROP TABLE public.users",
            "GRANT ALL ON public.users TO admin",
            "REVOKE ALL ON public.users FROM public",
            "SELECT 1; DROP TABLE public.users",
            "SELECT 1; DELETE FROM public.users",
            "SELECT * FROM public.users WHERE 1=1; SELECT * FROM pg_shadow",
            "SELECT COUNT(*) FROM public.users; SHUTDOWN",
            "SELECT COUNT(*) FROM public.users; CREATE USER evil",
            "SELECT COUNT(*) FROM public.users; SET ROLE administrator",
            "SELECT COUNT(*) FROM public.users; LOAD_FILE('/etc/passwd')",
            "SELECT COUNT(*) FROM information_schema.tables",
            "SELECT COUNT(*) FROM pg_catalog.pg_shadow",
            "SELECT COUNT(*) FROM public.users; COPY (SELECT secret FROM vault) TO '/tmp/stolen'",
    })
    void rejectsAdversarialSql(String sql) {
        assertThat(isRejected(sql))
                .as("SQL should be rejected: " + sql)
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "SELECT 1; EXEC xp_cmdshell('dir')",
            "'SELECT password FROM public.secrets -- credential extraction via comment obfuscation'",
            "'/**/UNION/**/SELECT password FROM public.users'",
            "SELECT COUNT(*) FROM public.users; SELECT * FROM public.users WHERE name = '' UNION SELECT password FROM public.users",
    })
    void rejectsObfuscationAndInjection(String sql) {
        assertThat(isRejected(sql))
                .as("SQL should be rejected: " + sql)
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "SELECT COUNT(*) FROM public.users",
            "SELECT id, name FROM public.users WHERE id = 1",
            "SELECT COUNT(*) FROM public.orders o JOIN public.users u ON o.user_id = u.id",
    })
    void acceptsLegitimateSelects(String sql) {
        assertThat(isRejected(sql))
                .as("SQL should be approved: " + sql)
                .isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "SELECT COUNT(*) FROM other_schema.users",
            "SELECT COUNT(*) FROM public.secrets",
            "SELECT COUNT(*) FROM information_schema.tables",
    })
    void rejectsUnauthorizedObjectAccess(String sql) {
        assertThat(isRejected(sql))
                .as("SQL should be rejected: " + sql)
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "WITH RECURSIVE cte AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM cte WHERE n < 100) SELECT * FROM cte",
            "WITH RECURSIVE t(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM t) SELECT * FROM t",
    })
    void rejectsRecursiveCtes(String sql) {
        assertThat(isRejected(sql))
                .as("Recursive CTEs should be rejected: " + sql)
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "SELECT 1 UNION SELECT * FROM public.users",
            "SELECT 1 UNION ALL SELECT * FROM public.users",
            "SELECT COUNT(*) FROM public.users UNION SELECT 1",
    })
    void rejectsSetOperations(String sql) {
        assertThat(isRejected(sql))
                .as("Set operations should be rejected: " + sql)
                .isTrue();
    }

    @Test
    void rejectsExcessiveCteDepth() {
        StringBuilder sb = new StringBuilder("WITH ");
        for (int i = 0; i < 10; i++) {
            if (i > 0) sb.append(", ");
            sb.append("cte").append(i).append(" AS (SELECT 1)");
        }
        sb.append(" SELECT * FROM cte9");
        assertThat(isRejected(sb.toString()))
                .as("Excessive CTE depth should be rejected")
                .isTrue();
    }

    @Test
    void rejectsExcessiveInputSize() {
        String largeSql = "SELECT " + "a,".repeat(10000) + " 1 FROM public.users";
        assertThat(isRejected(largeSql))
                .as("Excessive input size should be rejected")
                .isTrue();
    }

    @Test
    void rejectsEmptySql() {
        assertThat(isRejected(""))
                .as("Empty SQL should be rejected")
                .isTrue();
    }

    @Test
    void rejectsNullByteInjection() {
        assertThat(isRejected("SELECT 1\u0000; DROP TABLE public.users"))
                .as("Null byte injection should be rejected")
                .isTrue();
    }
}
