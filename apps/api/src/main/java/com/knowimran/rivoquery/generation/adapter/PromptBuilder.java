package com.knowimran.rivoquery.generation.adapter;

import com.knowimran.rivoquery.catalog.AuthorizedCatalogSlice;
import com.knowimran.rivoquery.catalog.CanonicalQualifiedName;
import com.knowimran.rivoquery.catalog.SchemaObject;
import com.knowimran.rivoquery.generation.GenerationRequest;
import java.util.stream.Collectors;

public final class PromptBuilder {

    private PromptBuilder() {
    }

    public static String build(GenerationRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a SQL assistant for a read-only database query system.\n\n");
        prompt.append("Database dialect: ").append(request.options().dialect()).append("\n\n");
        prompt.append("Question: ").append(request.question()).append("\n\n");
        prompt.append("Authorized schema objects:\n");

        for (SchemaObject object : request.catalogSlice().objects()) {
            CanonicalQualifiedName name = object.canonicalName();
            prompt.append("- ").append(name.schema()).append(".").append(name.object());
            if (object.description() != null && !object.description().isBlank()) {
                prompt.append(" (").append(object.description()).append(")");
            }
            prompt.append("\n");
            for (var column : object.columns()) {
                prompt.append("  - ").append(column.name());
                if (column.description() != null && !column.description().isBlank()) {
                    prompt.append(": ").append(column.description());
                }
                prompt.append(" [").append(column.type()).append("]");
                if (!column.nullable()) {
                    prompt.append(" NOT NULL");
                }
                prompt.append("\n");
            }
        }

        prompt.append("\nConstraints:\n");
        prompt.append("- Return only a single SELECT statement.\n");
        prompt.append("- Do not use DDL, DML, administrative commands, or stacked statements.\n");
        prompt.append("- Do not reference tables or columns outside the authorized schema objects listed above.\n");
        prompt.append("- Apply a LIMIT of ").append(request.options().maxRows()).append(" unless the question explicitly requests more.\n");
        prompt.append("- Provide a brief explanation, list of assumptions, referenced objects, confidence (0-100), and any warnings.\n");
        prompt.append("- If the question is ambiguous, respond with NEEDS_CLARIFICATION and a clarification question.\n");
        prompt.append("- If the question cannot be answered from the authorized schema, respond with UNANSWERABLE.\n");

        return prompt.toString();
    }
}
