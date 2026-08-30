package com.knowimran.rivoquery.catalog.application;

import com.knowimran.rivoquery.catalog.SchemaColumn;
import com.knowimran.rivoquery.catalog.SchemaObject;
import com.knowimran.rivoquery.catalog.SchemaObjectPage;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class SchemaSelectorService implements SchemaSelectorPort {
    private static final int EXACT_NAME_SCORE = 100;
    private static final int MAX_NAME_OVERLAP_SCORE = 80;
    private static final int MAX_DESCRIPTION_OVERLAP_SCORE = 60;
    private static final int MAX_JOIN_HINT_SCORE = 40;

    @Override
    public SchemaObjectPage selectRelevantObjects(
            WorkspaceId workspaceId,
            ConnectionId connectionId,
            String question,
            List<SchemaObject> candidates,
            SelectionPolicy policy) {
        List<SchemaObject> eligible = new ArrayList<>();
        for (SchemaObject candidate : candidates) {
            if (candidate.systemObject()) {
                continue;
            }
            eligible.add(candidate);
        }

        List<ScoredObject> scored = new ArrayList<>();
        List<String> questionTokens = tokenize(question);
        for (SchemaObject object : eligible) {
            int score = scoreObject(object, questionTokens);
            scored.add(new ScoredObject(object, score));
        }

        scored.sort(Comparator.<ScoredObject>comparingInt(s -> s.score)
                .reversed()
                .thenComparing(s -> s.object.canonicalName().qualified(), String::compareTo));

        List<SchemaObject> selected = new ArrayList<>();
        int selectedColumns = 0;
        int selectedTokens = 0;

        for (ScoredObject entry : scored) {
            if (selected.size() >= policy.maxTables()) {
                break;
            }
            int objectColumns = entry.object.columns().size();
            if (objectColumns > policy.maxColumnsPerTable()) {
                continue;
            }
            if (selectedColumns + objectColumns > policy.maxTotalColumns()) {
                continue;
            }
            int objectTokens = estimateTokens(entry.object);
            if (selectedTokens + objectTokens > policy.maxTokens()) {
                continue;
            }
            selected.add(entry.object);
            selectedColumns += objectColumns;
            selectedTokens += objectTokens;
        }

        return new SchemaObjectPage(selected, null);
    }

    private int scoreObject(SchemaObject object, List<String> questionTokens) {
        int score = 0;
        String canonicalQualified = object.canonicalName().qualified().toLowerCase(Locale.ROOT);
        String[] canonicalTokens = canonicalQualified.split("[^a-z0-9]+");
        List<String> canonicalTokenList = new ArrayList<>();
        for (String token : canonicalTokens) {
            if (!token.isBlank()) {
                canonicalTokenList.add(token);
            }
        }

        if (questionTokens.contains(canonicalQualified)) {
            score += EXACT_NAME_SCORE;
        }

        double nameOverlap = overlapRatio(questionTokens, canonicalTokenList);
        score += (int) Math.round(nameOverlap * MAX_NAME_OVERLAP_SCORE);

        if (object.description() != null) {
            List<String> descriptionTokens = tokenize(object.description());
            double descriptionOverlap = overlapRatio(questionTokens, descriptionTokens);
            score += (int) Math.round(descriptionOverlap * MAX_DESCRIPTION_OVERLAP_SCORE);
        }

        return Math.min(score, EXACT_NAME_SCORE + MAX_NAME_OVERLAP_SCORE + MAX_DESCRIPTION_OVERLAP_SCORE + MAX_JOIN_HINT_SCORE);
    }

    private List<String> tokenize(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        String[] parts = normalized.split("[^a-z0-9]+");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private double overlapRatio(List<String> questionTokens, List<String> candidateTokens) {
        if (questionTokens.isEmpty() || candidateTokens.isEmpty()) {
            return 0.0;
        }
        int matches = 0;
        for (String candidate : candidateTokens) {
            if (questionTokens.contains(candidate)) {
                matches++;
            }
        }
        return (double) matches / candidateTokens.size();
    }

    private int estimateTokens(SchemaObject object) {
        int tokens = 0;
        tokens += object.canonicalName().qualified().split("[^a-zA-Z0-9]+").length;
        if (object.description() != null) {
            tokens += object.description().split("[^a-zA-Z0-9]+").length;
        }
        for (SchemaColumn column : object.columns()) {
            tokens += column.name().split("[^a-zA-Z0-9]+").length;
            if (column.description() != null) {
                tokens += column.description().split("[^a-zA-Z0-9]+").length;
            }
        }
        return tokens;
    }

    private record ScoredObject(SchemaObject object, int score) {
    }
}
