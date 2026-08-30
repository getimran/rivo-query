package com.knowimran.rivoquery.querypolicy;

import com.knowimran.rivoquery.connection.DatabaseKind;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public interface SqlParserPort {

    record ParseResult(
            String canonicalSql,
            String fingerprint,
            List<String> referencedTables,
            List<String> referencedColumns,
            Map<String, List<String>> schemaObjects,
            int tableCount,
            int columnCount,
            int joinCount,
            int cteLevels,
            boolean hasSetOperation,
            boolean hasRecursiveCte,
            boolean isReadOnly,
            List<String> rejectedReasons) {

        public boolean isApproved() {
            return rejectedReasons.isEmpty();
        }
    }

    ParseResult parse(
            DatabaseKind dialect,
            String sql,
            List<String> allowedSchemas,
            List<String> allowedTables,
            int maxTables,
            int maxColumns,
            int maxJoins,
            int maxCteLevels,
            long deadlineMs) throws ParseException, TimeoutException;

    String canonicalize(DatabaseKind dialect, String sql) throws ParseException;

    String fingerprint(String sql) throws ParseException;

    class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }

        public ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class TimeoutException extends Exception {
        public TimeoutException(String message) {
            super(message);
        }
    }
}
