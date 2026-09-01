package com.knowimran.rivoquery.generation.adapter;

import com.knowimran.rivoquery.catalog.AuthorizedCatalogSlice;
import com.knowimran.rivoquery.catalog.CanonicalQualifiedName;
import com.knowimran.rivoquery.catalog.ColumnClassification;
import com.knowimran.rivoquery.catalog.ColumnTypeFamily;
import com.knowimran.rivoquery.catalog.RawQualifiedName;
import com.knowimran.rivoquery.catalog.SchemaColumn;
import com.knowimran.rivoquery.catalog.SchemaObject;
import com.knowimran.rivoquery.catalog.SchemaObjectType;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.generation.GenerationRequest;
import com.knowimran.rivoquery.identity.WorkspaceId;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    @Test
    void buildIncludesSchemaAndConstraints() {
        GenerationRequest request = new GenerationRequest(
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY"),
                new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFY"),
                "How many users?",
                new AuthorizedCatalogSlice(
                        new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY"),
                        new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFY"),
                        "fp_01J6MZ4H9ST5N7YQ2C8W3V0KFY",
                        List.of(new SchemaObject(
                                new RawQualifiedName("public", "users"),
                                new CanonicalQualifiedName("public", "users"),
                                SchemaObjectType.TABLE,
                                "Users table",
                                List.of(new SchemaColumn("id", ColumnTypeFamily.NUMERIC, true, "Primary key", ColumnClassification.NONE)),
                                false,
                                false
                        )),
                        200,
                        "postgresql",
                        List.of()
                ),
                List.of("select-only"),
                "v1",
                new GenerationRequest.GenerationOptions(200, "postgresql", List.of(), true)
        );

        String prompt = PromptBuilder.build(request);
        assertThat(prompt).contains("How many users?");
        assertThat(prompt).contains("public.users");
        assertThat(prompt).contains("Primary key");
        assertThat(prompt).contains("LIMIT of 200");
    }

    @Test
    void sanitizeDescriptionStripsNewlinesAndTruncates() {
        assertThat(PromptBuilder.sanitizeDescription(null)).isNull();
        assertThat(PromptBuilder.sanitizeDescription("")).isNull();
        assertThat(PromptBuilder.sanitizeDescription("  ")).isNull();
        assertThat(PromptBuilder.sanitizeDescription("single line")).isEqualTo("single line");
        assertThat(PromptBuilder.sanitizeDescription("line1\nline2\rline3")).isEqualTo("line1 line2 line3");

        String longDesc = "x".repeat(600);
        String truncated = PromptBuilder.sanitizeDescription(longDesc);
        assertThat(truncated).hasSize(503);
        assertThat(truncated).endsWith("...");
    }

    @Test
    void buildSanitizesDescriptions() {
        GenerationRequest request = new GenerationRequest(
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY"),
                new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFY"),
                "How many users?",
                new AuthorizedCatalogSlice(
                        new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY"),
                        new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFY"),
                        "fp_01J6MZ4H9ST5N7YQ2C8W3V0KFY",
                        List.of(new SchemaObject(
                                new RawQualifiedName("public", "users"),
                                new CanonicalQualifiedName("public", "users"),
                                SchemaObjectType.TABLE,
                                "Line1\nLine2\r\nLine3",
                                List.of(), false, false
                        )),
                        200,
                        "postgresql",
                        List.of()
                ),
                List.of("select-only"),
                "v1",
                new GenerationRequest.GenerationOptions(200, "postgresql", List.of(), true)
        );

        String prompt = PromptBuilder.build(request);
        assertThat(prompt).contains("Line1 Line2");
        assertThat(prompt).contains("Line3");
        assertThat(prompt).doesNotContain("\nLine2");
    }
}
