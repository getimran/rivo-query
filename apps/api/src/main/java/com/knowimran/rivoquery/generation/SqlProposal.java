package com.knowimran.rivoquery.generation;

import com.knowimran.rivoquery.catalog.CanonicalQualifiedName;
import java.util.List;
import java.util.Objects;

public record SqlProposal(
        String sql,
        String explanation,
        List<String> assumptions,
        List<CanonicalQualifiedName> referencedObjects,
        AmbiguityStatus ambiguityStatus,
        String clarificationQuestion,
        int confidence,
        List<String> warnings) {

    public SqlProposal {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(explanation, "explanation");
        Objects.requireNonNull(assumptions, "assumptions");
        Objects.requireNonNull(referencedObjects, "referencedObjects");
        Objects.requireNonNull(ambiguityStatus, "ambiguityStatus");
        Objects.requireNonNull(warnings, "warnings");
        if (sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be blank");
        }
        if (confidence < 0 || confidence > 100) {
            throw new IllegalArgumentException("confidence must be between 0 and 100");
        }
        if (ambiguityStatus == AmbiguityStatus.NEEDS_CLARIFICATION && (clarificationQuestion == null || clarificationQuestion.isBlank())) {
            throw new IllegalArgumentException("clarificationQuestion is required when status is NEEDS_CLARIFICATION");
        }
    }
}
