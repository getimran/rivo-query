package com.knowimran.rivoquery.generation;

import com.knowimran.rivoquery.catalog.AuthorizedCatalogSlice;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.List;
import java.util.Objects;

public record GenerationRequest(
        WorkspaceId workspaceId,
        ConnectionId connectionId,
        String question,
        AuthorizedCatalogSlice catalogSlice,
        List<String> serverFeatureConstraints,
        String promptVersion,
        GenerationOptions options) {

    public GenerationRequest {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(catalogSlice, "catalogSlice");
        Objects.requireNonNull(serverFeatureConstraints, "serverFeatureConstraints");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(options, "options");
        if (question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (serverFeatureConstraints.isEmpty()) {
            throw new IllegalArgumentException("serverFeatureConstraints must not be empty");
        }
    }

    public record GenerationOptions(
            int maxRows,
            String dialect,
            List<String> allowedFunctions,
            boolean includeExplanations) {

        public GenerationOptions {
            Objects.requireNonNull(dialect, "dialect");
            Objects.requireNonNull(allowedFunctions, "allowedFunctions");
            if (maxRows <= 0) {
                throw new IllegalArgumentException("maxRows must be positive");
            }
            if (!dialect.equals("postgresql") && !dialect.equals("mysql")) {
                throw new IllegalArgumentException("unsupported dialect: " + dialect);
            }
        }
    }
}
