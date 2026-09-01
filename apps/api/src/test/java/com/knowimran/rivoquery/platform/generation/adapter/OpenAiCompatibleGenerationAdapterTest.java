package com.knowimran.rivoquery.platform.generation.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.generation.AmbiguityStatus;
import com.knowimran.rivoquery.generation.SqlProposal;
import com.knowimran.rivoquery.generation.SqlGenerationModel.GenerationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleGenerationAdapterTest {

    static final String VALID_JSON =
            "{\"sql\":\"SELECT COUNT(*) FROM public.users\","
            + "\"explanation\":\"Counts all users\","
            + "\"assumptions\":[\"Table users exists\"],"
            + "\"referenced_objects\":[\"public.users\"],"
            + "\"ambiguity_status\":\"READY\","
            + "\"confidence\":95,"
            + "\"warnings\":[]}";

    static Map<String, Object> validResponse(String content) {
        return Map.of("choices", List.of(
                Map.of("message", Map.of("content", content))));
    }

    @Test
    void parsesValidResponse() throws Exception {
        SqlProposal result = OpenAiCompatibleGenerationAdapter.parseResponse(
                validResponse(VALID_JSON));

        assertThat(result.sql()).contains("COUNT(*)");
        assertThat(result.explanation()).isEqualTo("Counts all users");
        assertThat(result.ambiguityStatus()).isEqualTo(AmbiguityStatus.READY);
        assertThat(result.confidence()).isEqualTo(95);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void throwsOnNullResponse() {
        assertThatThrownBy(() -> OpenAiCompatibleGenerationAdapter.parseResponse(null))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("invalid response");
    }

    @Test
    void throwsOnMissingChoices() {
        assertThatThrownBy(() -> OpenAiCompatibleGenerationAdapter.parseResponse(
                Map.of("unexpected", "shape")))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("invalid response");
    }

    @Test
    void throwsOnEmptyChoices() {
        assertThatThrownBy(() -> OpenAiCompatibleGenerationAdapter.parseResponse(
                Map.of("choices", List.of())))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("empty choices");
    }

    @Test
    void throwsOnMalformedJson() {
        assertThatThrownBy(() -> OpenAiCompatibleGenerationAdapter.parseResponse(
                validResponse("not valid json")))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("failed to parse model response");
    }

    @Test
    void parsesNeedsClarification() throws Exception {
        String json =
                "{\"sql\":\"N/A\","
                + "\"explanation\":\"N/A\","
                + "\"assumptions\":[],"
                + "\"referenced_objects\":[],"
                + "\"ambiguity_status\":\"NEEDS_CLARIFICATION\","
                + "\"clarification_question\":\"Should revenue use gross or net?\","
                + "\"confidence\":0,"
                + "\"warnings\":[]}";

        SqlProposal result = OpenAiCompatibleGenerationAdapter.parseResponse(
                validResponse(json));

        assertThat(result.ambiguityStatus()).isEqualTo(AmbiguityStatus.NEEDS_CLARIFICATION);
        assertThat(result.clarificationQuestion()).contains("gross or net");
    }
}
