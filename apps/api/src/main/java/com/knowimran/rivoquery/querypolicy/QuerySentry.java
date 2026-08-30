package com.knowimran.rivoquery.querypolicy;

import com.knowimran.rivoquery.catalog.AuthorizedCatalogSlice;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.generation.AmbiguityStatus;
import com.knowimran.rivoquery.generation.SqlProposal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class QuerySentry {

    private final SqlParserPort parser;
    private final String policyVersion;

    public QuerySentry(SqlParserPort parser, String policyVersion) {
        this.parser = parser;
        this.policyVersion = policyVersion;
    }

    public ApprovedQuery evaluate(
            SqlProposal proposal,
            AuthorizedCatalogSlice catalogSlice,
            DatabaseKind dialect,
            QueryPolicy policy) throws QueryPolicyException {

        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(catalogSlice, "catalogSlice");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(policy, "policy");

        if (proposal.ambiguityStatus() != AmbiguityStatus.READY) {
            throw new QueryPolicyException("proposal is not ready for evaluation: " + proposal.ambiguityStatus());
        }

        try {
            SqlParserPort.ParseResult result = parser.parse(
                    dialect,
                    proposal.sql(),
                    catalogSlice.objects().stream()
                            .map(o -> o.canonicalName().schema())
                            .distinct()
                            .toList(),
                    catalogSlice.objects().stream()
                            .map(o -> o.canonicalName().qualified())
                            .distinct()
                            .toList(),
                    policy.maxTables(),
                    policy.maxColumns(),
                    policy.maxJoins(),
                    policy.maxCteLevels(),
                    policy.parseDeadlineMs()
            );

            if (!result.isApproved()) {
                throw new QueryPolicyException("SQL rejected by parser: " + String.join(", ", result.rejectedReasons()));
            }

            Map<String, Object> limits = Map.of(
                    "maxRows", Math.min(catalogSlice.maxRows(), policy.maxRows()),
                    "maxColumns", result.columnCount(),
                    "maxTables", result.tableCount(),
                    "maxJoins", result.joinCount(),
                    "maxCteLevels", result.cteLevels()
            );

            Instant now = Instant.now();
            return new ApprovedQuery(
                    UUID.randomUUID().toString(),
                    dialect,
                    result.canonicalSql(),
                    result.fingerprint(),
                    result.referencedTables(),
                    List.of(),
                    limits,
                    policyVersion,
                    now,
                    now.plusSeconds(policy.previewTtlSeconds())
            );
        } catch (SqlParserPort.TimeoutException e) {
            throw new QueryPolicyException("parser timeout", e);
        } catch (SqlParserPort.ParseException e) {
            throw new QueryPolicyException("parse error", e);
        }
    }

    public record QueryPolicy(
            int maxTables,
            int maxColumns,
            int maxJoins,
            int maxCteLevels,
            int maxRows,
            long parseDeadlineMs,
            int previewTtlSeconds) {

        public QueryPolicy {
            if (maxTables < 1) throw new IllegalArgumentException("maxTables must be at least 1");
            if (maxColumns < 1) throw new IllegalArgumentException("maxColumns must be at least 1");
            if (maxJoins < 0) throw new IllegalArgumentException("maxJoins must not be negative");
            if (maxCteLevels < 0) throw new IllegalArgumentException("maxCteLevels must not be negative");
            if (maxRows < 1) throw new IllegalArgumentException("maxRows must be at least 1");
            if (parseDeadlineMs <= 0) throw new IllegalArgumentException("parseDeadlineMs must be positive");
            if (previewTtlSeconds <= 0) throw new IllegalArgumentException("previewTtlSeconds must be positive");
        }
    }

    public static class QueryPolicyException extends Exception {
        public QueryPolicyException(String message) {
            super(message);
        }

        public QueryPolicyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
