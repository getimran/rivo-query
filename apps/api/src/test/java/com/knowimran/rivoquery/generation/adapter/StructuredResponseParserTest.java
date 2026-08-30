package com.knowimran.rivoquery.generation.adapter;

import com.knowimran.rivoquery.catalog.CanonicalQualifiedName;
import com.knowimran.rivoquery.generation.AmbiguityStatus;
import com.knowimran.rivoquery.generation.SqlProposal;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredResponseParserTest {

    @Test
    void parseValidResponse() throws Exception {
        String json = """
                {
                  "ambiguity_status": "READY",
                  "sql": "SELECT * FROM users",
                  "explanation": "Returns all users",
                  "assumptions": ["table exists"],
                  "referenced_objects": ["public.users"],
                  "clarification_question": null,
                  "confidence": 90,
                  "warnings": []
                }
                """;

        SqlProposal proposal = StructuredResponseParser.parse(json);
        assertThat(proposal.ambiguityStatus()).isEqualTo(AmbiguityStatus.READY);
        assertThat(proposal.sql()).isEqualTo("SELECT * FROM users");
        assertThat(proposal.referencedObjects()).contains(new CanonicalQualifiedName("public", "users"));
        assertThat(proposal.confidence()).isEqualTo(90);
    }

    @Test
    void parseRejectsEmptyResponse() {
        assertThatThrownBy(() -> StructuredResponseParser.parse(""))
                .isInstanceOf(StructuredResponseParser.ParseException.class);
    }

    @Test
    void parseRejectsMalformedResponse() {
        assertThatThrownBy(() -> StructuredResponseParser.parse("not json"))
                .isInstanceOf(StructuredResponseParser.ParseException.class);
    }
}
