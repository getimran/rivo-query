package com.knowimran.rivoquery.querypolicy;

import com.knowimran.rivoquery.catalog.AuthorizedCatalogSlice;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.generation.SqlProposal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ApprovedQuery(
        String queryId,
        DatabaseKind dialect,
        String canonicalSql,
        String sqlFingerprint,
        List<String> referencedObjects,
        List<String> risks,
        Map<String, Object> enforcedLimits,
        String policyVersion,
        Instant approvedAt,
        Instant expiresAt) {

    public ApprovedQuery {
        Objects.requireNonNull(queryId, "queryId");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(canonicalSql, "canonicalSql");
        Objects.requireNonNull(sqlFingerprint, "sqlFingerprint");
        Objects.requireNonNull(referencedObjects, "referencedObjects");
        Objects.requireNonNull(risks, "risks");
        Objects.requireNonNull(enforcedLimits, "enforcedLimits");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(approvedAt, "approvedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (queryId.isBlank()) {
            throw new IllegalArgumentException("queryId must not be blank");
        }
        if (canonicalSql.isBlank()) {
            throw new IllegalArgumentException("canonicalSql must not be blank");
        }
        if (sqlFingerprint.isBlank()) {
            throw new IllegalArgumentException("sqlFingerprint must not be blank");
        }
        if (approvedAt.isAfter(expiresAt)) {
            throw new IllegalArgumentException("approvedAt must be before expiresAt");
        }
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
