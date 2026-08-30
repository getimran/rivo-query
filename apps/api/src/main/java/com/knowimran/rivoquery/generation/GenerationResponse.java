package com.knowimran.rivoquery.generation;

import com.knowimran.rivoquery.generation.SqlProposal;
import java.util.Objects;

public record GenerationResponse(
        AmbiguityStatus status,
        SqlProposal proposal,
        String promptVersion,
        String modelId,
        long latencyMs) {

    public GenerationResponse {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(modelId, "modelId");
        if (promptVersion.isBlank()) {
            throw new IllegalArgumentException("promptVersion must not be blank");
        }
        if (modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }
    }
}
