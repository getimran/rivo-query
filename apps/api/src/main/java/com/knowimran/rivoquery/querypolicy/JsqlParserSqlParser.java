package com.knowimran.rivoquery.querypolicy;

import com.knowimran.rivoquery.connection.DatabaseKind;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.util.TablesNamesFinder;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.SelectDeParser;
import net.sf.jsqlparser.util.deparser.StatementDeParser;
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
            long deadlineMs) throws ParseException, TimeoutException {

        if (sql == null || sql.isBlank()) {
            throw new ParseException("SQL must not be blank");
        }
        if (sql.length() > MAX_INPUT_SIZE) {
            throw new ParseException("SQL exceeds maximum input size of " + MAX_INPUT_SIZE + " bytes");
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
                throw new ParseException("failed to parse SQL: " + je.getMessage(), je);
            }
            throw new ParseException("parser execution failed", e);
        }

        if (statement == null) {
            throw new ParseException("parser returned null statement");
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

        TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
        List<String> referencedTables;
        try {
            referencedTables = tablesNamesFinder.getTableList(statement);
        } catch (Exception e) {
            rejectedReasons.add("failed to extract table references: " + e.getMessage());
            referencedTables = List.of();
        }

        int joinCount = countJoins(plainSelect);
        boolean hasSetOperation = select.getSelectBody() instanceof SetOperationList;

        if (referencedTables.size() > maxTables) {
            rejectedReasons.add("table count " + referencedTables.size() + " exceeds maximum " + maxTables);
        }
        if (hasSetOperation) {
            rejectedReasons.add("set operations are not allowed");
        }

        if (!rejectedReasons.isEmpty()) {
            return new ParseResult(
                    "", "", referencedTables, List.of(), Map.of(), referencedTables.size(), 0, joinCount,
                    select.getWithItemsList() != null ? select.getWithItemsList().size() : 0,
                    hasSetOperation, select.getWithItemsList() != null && select.getWithItemsList().stream().anyMatch(WithItem::isRecursive),
                    true, rejectedReasons);
        }

        String canonicalSql = canonicalize(dialect, sql);
        String fingerprint = fingerprint(sql);

        return new ParseResult(
                canonicalSql,
                fingerprint,
                referencedTables,
                List.of(),
                Map.of(),
                referencedTables.size(),
                0,
                joinCount,
                select.getWithItemsList() != null ? select.getWithItemsList().size() : 0,
                hasSetOperation,
                select.getWithItemsList() != null && select.getWithItemsList().stream().anyMatch(WithItem::isRecursive),
                true,
                rejectedReasons
        );
    }

    @Override
    public String canonicalize(DatabaseKind dialect, String sql) throws ParseException {
        if (sql == null || sql.isBlank()) {
            throw new ParseException("SQL must not be blank");
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            StringBuilder sb = new StringBuilder();
            SelectDeParser selectDeParser = new SelectDeParser(sb);
            ExpressionDeParser expressionDeParser = new ExpressionDeParser(selectDeParser, sb);
            selectDeParser.setExpressionVisitor(expressionDeParser);
            StatementDeParser statementDeParser = new StatementDeParser(expressionDeParser, selectDeParser, sb);
            statement.accept(statementDeParser);
            return sb.toString().trim();
        } catch (JSQLParserException e) {
            throw new ParseException("failed to canonicalize SQL: " + e.getMessage(), e);
        }
    }

    @Override
    public String fingerprint(String sql) throws ParseException {
        if (sql == null || sql.isBlank()) {
            throw new ParseException("SQL must not be blank");
        }
        try {
            String canonical = canonicalize(DatabaseKind.POSTGRESQL, sql);
            return Integer.toHexString(Objects.hash(canonical));
        } catch (ParseException e) {
            throw new ParseException("failed to fingerprint SQL: " + e.getMessage(), e);
        }
    }

    private Statement parseWithTimeout(String sql, long deadlineMs) throws SqlParserPort.TimeoutException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Statement> future = executor.submit(() -> {
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
            throw new SqlParserPort.TimeoutException("parser timeout after " + deadlineMs + "ms");
        } finally {
            executor.shutdownNow();
        }
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
}
