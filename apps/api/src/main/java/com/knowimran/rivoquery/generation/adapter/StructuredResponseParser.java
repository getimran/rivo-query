package com.knowimran.rivoquery.generation.adapter;

import com.knowimran.rivoquery.catalog.CanonicalQualifiedName;
import com.knowimran.rivoquery.generation.AmbiguityStatus;
import com.knowimran.rivoquery.generation.SqlProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class StructuredResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StructuredResponseParser() {
    }

    @SuppressWarnings("unchecked")
    public static SqlProposal parse(String content) throws ParseException {
        if (content == null || content.isBlank()) {
            throw new ParseException("empty model response");
        }

        try {
            Map<String, Object> parsed = MAPPER.readValue(content.trim(), Map.class);
            String ambiguity = (String) parsed.getOrDefault("ambiguity_status", "READY");
            AmbiguityStatus status = AmbiguityStatus.valueOf(ambiguity.toUpperCase());

            String sql = (String) parsed.getOrDefault("sql", "");
            String explanation = (String) parsed.getOrDefault("explanation", "");
            List<Object> rawAssumptions = (List<Object>) parsed.getOrDefault("assumptions", List.of());
            List<String> assumptions = new ArrayList<>();
            for (Object o : rawAssumptions) {
                if (o != null) assumptions.add(o.toString());
            }

            List<Object> rawObjects = (List<Object>) parsed.getOrDefault("referenced_objects", List.of());
            List<CanonicalQualifiedName> referencedObjects = new ArrayList<>();
            for (Object o : rawObjects) {
                if (o instanceof String s && s.contains(".")) {
                    int idx = s.lastIndexOf('.');
                    referencedObjects.add(new CanonicalQualifiedName(s.substring(0, idx), s.substring(idx + 1)));
                }
            }

            String clarificationQuestion = (String) parsed.getOrDefault("clarification_question", null);
            Object confidenceObj = parsed.getOrDefault("confidence", 50);
            int confidence = confidenceObj instanceof Number n ? n.intValue() : 50;
            List<Object> rawWarnings = (List<Object>) parsed.getOrDefault("warnings", List.of());
            List<String> warnings = new ArrayList<>();
            for (Object o : rawWarnings) {
                if (o != null) warnings.add(o.toString());
            }

            return new SqlProposal(
                    sql,
                    explanation,
                    assumptions,
                    referencedObjects,
                    status,
                    clarificationQuestion,
                    Math.max(0, Math.min(100, confidence)),
                    warnings
            );
        } catch (Exception e) {
            throw new ParseException("failed to parse model response: " + e.getMessage(), e);
        }
    }

    public static class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }

        public ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
