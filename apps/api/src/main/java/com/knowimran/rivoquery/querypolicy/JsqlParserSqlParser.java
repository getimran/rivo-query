package com.knowimran.rivoquery.querypolicy;

import com.knowimran.rivoquery.connection.DatabaseKind;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.SelectDeParser;
import net.sf.jsqlparser.util.deparser.StatementDeParser;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class JsqlParserSqlParser implements SqlParserPort {

    private static final long DEFAULT_DEADLINE_MS = 500;
    private static final int MAX_INPUT_SIZE = 64 * 1024;

    @Override
    public ParseResult parse(
            DatabaseKind dialect,
            String sql,
            List<String> allowedSchemas,
            List<String> allowedTables,
            int maxTables,
            int maxColumns,
            int maxJoins,
            int maxCteLevels,
            long deadlineMs) throws SqlParserPort.ParseException, SqlParserPort.TimeoutException {

        if (sql == null || sql.isBlank()) {
            throw new SqlParserPort.ParseException("SQL must not be blank");
        }
        if (sql.length() > MAX_INPUT_SIZE) {
            throw new SqlParserPort.ParseException("SQL exceeds maximum input size of " + MAX_INPUT_SIZE + " bytes");
        }
        if (deadlineMs <= 0) {
            deadlineMs = DEFAULT_DEADLINE_MS;
        }

        List<String> rejectedReasons = new ArrayList<>();
        Statement statement;
        try {
            statement = parseWithTimeout(sql, deadlineMs);
        } catch (SqlParserPort.TimeoutException e) {
            throw new SqlParserPort.TimeoutException("parser timeout after " + deadlineMs + "ms");
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof JSQLParserException je) {
                throw new SqlParserPort.ParseException("failed to parse SQL: " + je.getMessage(), je);
            }
            throw new SqlParserPort.ParseException("parser execution failed", e);
        }

        if (statement == null) {
            throw new SqlParserPort.ParseException("parser returned null statement");
        }

        if (!(statement instanceof Select)) {
            rejectedReasons.add("only SELECT statements are accepted");
            return new ParseResult("", "", List.of(), List.of(), Map.of(), 0, 0, 0, 0, false, false, false, rejectedReasons);
        }

        Select select = (Select) statement;
        PlainSelect plainSelect = select.getSelectBody() instanceof PlainSelect ps ? ps : null;

        if (plainSelect == null) {
            rejectedReasons.add("unsupported SELECT structure");
            return new ParseResult("", "", List.of(), List.of(), Map.of(), 0, 0, 0, 0, false, false, false, rejectedReasons);
        }

        if (plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty()) {
            rejectedReasons.add("SELECT INTO is not allowed");
        }

        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            int cteLevels = select.getWithItemsList().size();
            if (cteLevels > maxCteLevels) {
                rejectedReasons.add("CTE depth " + cteLevels + " exceeds maximum " + maxCteLevels);
            }
            for (WithItem withItem : select.getWithItemsList()) {
                if (withItem.isRecursive()) {
                    rejectedReasons.add("recursive CTEs are not allowed");
                    break;
                }
            }
        }

        List<String> referencedTables;
        List<String> referencedColumns;
        Map<String, List<String>> schemaObjects;
        try {
            referencedTables = extractTables(statement);
            referencedColumns = extractColumns(statement);
            schemaObjects = extractSchemaObjects(referencedTables, referencedColumns);
        } catch (Exception e) {
            rejectedReasons.add("failed to extract table references: " + e.getMessage());
            referencedTables = List.of();
            referencedColumns = List.of();
            schemaObjects = Map.of();
        }

        List<String> authorizationErrors = checkAuthorization(referencedTables, allowedSchemas, allowedTables);
        rejectedReasons.addAll(authorizationErrors);

        List<String> functionErrors = checkForbiddenFunctions(canonicalize(statement));
        rejectedReasons.addAll(functionErrors);

        int joinCount = countJoins(plainSelect);
        boolean hasSetOperation = select.getSelectBody() instanceof SetOperationList;

        if (referencedTables.size() > maxTables) {
            rejectedReasons.add("table count " + referencedTables.size() + " exceeds maximum " + maxTables);
        }
        if (referencedColumns.size() > maxColumns) {
            rejectedReasons.add("column count " + referencedColumns.size() + " exceeds maximum " + maxColumns);
        }
        if (joinCount > maxJoins) {
            rejectedReasons.add("join count " + joinCount + " exceeds maximum " + maxJoins);
        }
        if (hasSetOperation) {
            rejectedReasons.add("set operations are not allowed");
        }

        if (!rejectedReasons.isEmpty()) {
            return new ParseResult(
                    "", "", referencedTables, referencedColumns, schemaObjects, referencedTables.size(), referencedColumns.size(), joinCount,
                    select.getWithItemsList() != null ? select.getWithItemsList().size() : 0,
                    hasSetOperation, select.getWithItemsList() != null && select.getWithItemsList().stream().anyMatch(WithItem::isRecursive),
                    true, rejectedReasons);
        }

        String canonicalSql = canonicalize(statement);
        String fingerprint = fingerprint(canonicalSql);

        return new ParseResult(
                canonicalSql,
                fingerprint,
                referencedTables,
                referencedColumns,
                schemaObjects,
                referencedTables.size(),
                referencedColumns.size(),
                joinCount,
                select.getWithItemsList() != null ? select.getWithItemsList().size() : 0,
                hasSetOperation,
                select.getWithItemsList() != null && select.getWithItemsList().stream().anyMatch(WithItem::isRecursive),
                true,
                rejectedReasons
        );
    }

    @Override
    public String canonicalize(DatabaseKind dialect, String sql) throws SqlParserPort.ParseException {
        if (sql == null || sql.isBlank()) {
            throw new SqlParserPort.ParseException("SQL must not be blank");
        }
        try {
            Statement statement = parseWithTimeout(sql, 500);
            return canonicalize(statement);
        } catch (SqlParserPort.TimeoutException e) {
            throw new SqlParserPort.ParseException("parser timeout during canonicalization: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof JSQLParserException je) {
                throw new SqlParserPort.ParseException("failed to parse SQL for canonicalization: " + je.getMessage(), je);
            }
            throw new SqlParserPort.ParseException("failed to canonicalize SQL", e);
        }
    }

    String canonicalize(Statement statement) {
        StringBuilder sb = new StringBuilder();
        SelectDeParser selectDeParser = new SelectDeParser(sb);
        ExpressionDeParser expressionDeParser = new ExpressionDeParser(selectDeParser, sb);
        selectDeParser.setExpressionVisitor(expressionDeParser);
        StatementDeParser statementDeParser = new StatementDeParser(expressionDeParser, selectDeParser, sb);
        statement.accept(statementDeParser);
        return sb.toString().trim();
    }

    @Override
    public String fingerprint(String sql) throws SqlParserPort.ParseException {
        if (sql == null || sql.isBlank()) {
            throw new SqlParserPort.ParseException("SQL must not be blank");
        }
        try {
            String canonical = canonicalize(DatabaseKind.POSTGRESQL, sql);
            return sha256(canonical);
        } catch (SqlParserPort.ParseException e) {
            throw new SqlParserPort.ParseException("failed to fingerprint SQL: " + e.getMessage(), e);
        }
    }

    private Statement parseWithTimeout(String sql, long deadlineMs) throws SqlParserPort.TimeoutException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Statement> future = null;
        try {
            future = executor.submit(() -> {
                try {
                    return CCJSqlParserUtil.parse(sql);
                } catch (JSQLParserException e) {
                    throw new RuntimeException(e);
                }
            });
            return future.get(deadlineMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SqlParserPort.TimeoutException("parser interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof JSQLParserException je) {
                throw new RuntimeException(je);
            }
            throw new RuntimeException(cause);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new SqlParserPort.TimeoutException("parser timeout after " + deadlineMs + "ms");
        } finally {
            executor.shutdownNow();
        }
    }

    private List<String> extractTables(Statement statement) {
        List<String> tables = new ArrayList<>();
        if (statement instanceof Select select) {
            collectTablesFromSelect(select, tables);
        }
        return tables;
    }

    private void collectTablesFromSelect(Select select, List<String> tables) {
        if (select.getSelectBody() instanceof PlainSelect plainSelect) {
            collectTablesFromPlainSelect(plainSelect, tables);
        } else if (select.getSelectBody() instanceof SetOperationList setOperationList) {
            for (Select subSelect : setOperationList.getSelects()) {
                if (subSelect instanceof PlainSelect ps) {
                    collectTablesFromPlainSelect(ps, tables);
                }
            }
        }
        if (select.getWithItemsList() != null) {
            for (WithItem withItem : select.getWithItemsList()) {
                if (withItem.getSelectBody() instanceof Select subSelect) {
                    collectTablesFromSelect(subSelect, tables);
                }
            }
        }
    }

    private void collectTablesFromPlainSelect(PlainSelect plainSelect, List<String> tables) {
        collectTablesFromFromItem(plainSelect.getFromItem(), tables);
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                collectTablesFromFromItem(join.getRightItem(), tables);
            }
        }
    }

    private void collectTablesFromFromItem(net.sf.jsqlparser.statement.select.FromItem item, List<String> tables) {
        if (item instanceof Table table) {
            tables.add(qualifiedName(table));
        } else if (item instanceof ParenthesedSelect parenthesizedSelect) {
            collectTablesFromSelect(parenthesizedSelect.getSelect(), tables);
        } else if (item instanceof ParenthesedFromItem parenthesizedFromItem) {
            collectTablesFromFromItem(parenthesizedFromItem.getFromItem(), tables);
        }
    }

    private String qualifiedName(Table table) {
        String schema = table.getSchemaName();
        String name = table.getName();
        if (schema != null && !schema.isBlank()) {
            return schema + "." + name;
        }
        return name;
    }

    private List<String> extractColumns(Statement statement) {
        List<String> columns = new ArrayList<>();
        if (statement instanceof Select select) {
            if (select.getSelectBody() instanceof PlainSelect plainSelect) {
                for (SelectItem<?> item : plainSelect.getSelectItems()) {
                    extractColumnFromSelectItem(item, columns);
                }
            }
        }
        return columns;
    }

    private void extractColumnFromSelectItem(SelectItem<?> item, List<String> columns) {
        if (item.getExpression() instanceof Column column) {
            String tableName = column.getTable() != null ? column.getTable().getName() : null;
            String columnName = column.getColumnName();
            if (tableName != null && !tableName.isBlank()) {
                columns.add(tableName + "." + columnName);
            } else {
                columns.add(columnName);
            }
        }
    }

    private Map<String, List<String>> extractSchemaObjects(List<String> tables, List<String> columns) {
        Map<String, List<String>> schemaObjects = new LinkedHashMap<>();
        for (String table : tables) {
            schemaObjects.putIfAbsent(table, new ArrayList<>());
        }
        for (String column : columns) {
            String table = column.contains(".") ? column.substring(0, column.lastIndexOf('.')) : "unknown";
            schemaObjects.putIfAbsent(table, new ArrayList<>());
            schemaObjects.get(table).add(column.contains(".") ? column.substring(column.lastIndexOf('.') + 1) : column);
        }
        return schemaObjects;
    }

    private List<String> checkAuthorization(List<String> referencedTables, List<String> allowedSchemas, List<String> allowedTables) {
        List<String> errors = new ArrayList<>();
        Set<String> allowedTableSet = new HashSet<>(allowedTables);
        Set<String> allowedSchemaSet = new HashSet<>(allowedSchemas);
        for (String table : referencedTables) {
            String canonicalTable = normalizeForAuthorization(table);
            boolean tableAllowed = allowedTableSet.isEmpty() || allowedTableSet.contains(canonicalTable);
            boolean schemaAllowed = allowedSchemaSet.isEmpty();
            if (!schemaAllowed && canonicalTable.contains(".")) {
                String schema = canonicalTable.substring(0, canonicalTable.lastIndexOf('.'));
                schemaAllowed = allowedSchemaSet.contains(schema);
            } else if (!schemaAllowed) {
                schemaAllowed = allowedSchemaSet.contains(canonicalTable);
            }
            if (!tableAllowed || !schemaAllowed) {
                errors.add("table " + table + " is not in the authorized catalog");
            }
        }
        return errors;
    }

    private List<String> checkForbiddenFunctions(String canonicalSql) {
        List<String> errors = new ArrayList<>();
        Set<String> forbidden = Set.of(
                "pg_read_file", "pg_write_file", "pg_read_binary_file", "pg_stat_file",
                "lo_import", "lo_export", "lo_read", "lo_write",
                "load_file", "intooutfile", "intodumefile",
                "pg_sleep", "pg_backend_pid", "pg_stat_activity", "pg_stat_replication",
                "pg_create_restore_point", "pg_switch_wal", "pg_walfile_name",
                "current_setting", "set_config",
                "dblink", "dblink_exec", "dblink_connect",
                "copy"
        );

        String upperSql = canonicalSql.toUpperCase(Locale.ROOT);
        for (String fn : forbidden) {
            if (upperSql.contains(" " + fn.toUpperCase(Locale.ROOT) + "(") ||
                upperSql.contains("(" + fn.toUpperCase(Locale.ROOT) + "(")) {
                errors.add("forbidden function: " + fn);
            }
        }
        return errors;
    }

    private static String normalizeForAuthorization(String qualified) {
        return qualified.toLowerCase(Locale.ROOT);
    }

    private int countJoins(PlainSelect plainSelect) {
        int count = 0;
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                if (!join.isSimple()) {
                    count++;
                }
            }
        }
        return count;
    }

    private String sha256(String value) throws SqlParserPort.ParseException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new ParseException("failed to compute fingerprint", e);
        }
    }
}
