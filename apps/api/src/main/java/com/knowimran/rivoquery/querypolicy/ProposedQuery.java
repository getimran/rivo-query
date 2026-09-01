package com.knowimran.rivoquery.querypolicy;

import java.util.List;
import java.util.Objects;

public record ProposedQuery(
        String sql,
        AmbiguityStatus ambiguityStatus,
        int confidence,
        List<String> warnings) {

    public ProposedQuery {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be blank");
        }
        if (ambiguityStatus == null) {
            throw new IllegalArgumentException("ambiguityStatus must not be null");
        }
        if (confidence < 0 || confidence > 100) {
            throw new IllegalArgumentException("confidence must be between 0 and 100");
        }
        if (warnings == null) {
            warnings = List.of();
        }
    }

    public enum AmbiguityStatus {
        READY,
        NEEDS_CLARIFICATION,
        UNANSWERABLE
    }
}
