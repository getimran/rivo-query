package com.knowimran.rivoquery.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowimran.rivoquery.catalog.application.SchemaSelectorPort;
import com.knowimran.rivoquery.catalog.application.SchemaSelectorService;
import com.knowimran.rivoquery.catalog.SystemObjectDenylist;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.identity.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaSelectorRecallTest {
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final ConnectionId CONNECTION = new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX");

    @Test
    void recallFixturesMatchExpectedSelection() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RecallFixtures fixtures;
        try (InputStream is = getClass().getResourceAsStream("/evals/schema-selection/recall-fixtures.json")) {
            assertThat(is).as("recall-fixtures.json must exist on test classpath").isNotNull();
            fixtures = mapper.readValue(is, RecallFixtures.class);
        }

        SchemaSelectorService selector = new SchemaSelectorService();
        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (RecallCase c : fixtures.cases()) {
            List<SchemaObject> candidates = new ArrayList<>();
            for (String qualified : c.candidates()) {
                String[] parts = qualified.split("\\.", 2);
                boolean systemObject = SystemObjectDenylist.isSystemSchema(com.knowimran.rivoquery.connection.DatabaseKind.POSTGRESQL, parts[0]);
                candidates.add(new SchemaObject(
                        new RawQualifiedName(parts[0], parts[1]),
                        new CanonicalQualifiedName(parts[0], parts[1]),
                        SchemaObjectType.TABLE,
                        descriptionFor(qualified),
                        List.of(new SchemaColumn("id", ColumnTypeFamily.STRING, true, "id", ColumnClassification.NONE)),
                        systemObject,
                        false
                ));
            }

            SchemaSelectorPort.SelectionPolicy policy = new SchemaSelectorPort.SelectionPolicy(
                    c.maxTables() != null ? c.maxTables() : 10,
                    50,
                    200,
                    2,
                    4000
            );

            var result = selector.selectRelevantObjects(WORKSPACE, CONNECTION, c.question(), candidates, policy);
            List<String> actual = new ArrayList<>();
            for (SchemaObject o : result.items()) {
                actual.add(o.canonicalName().schema() + "." + o.canonicalName().object());
            }

            if (actual.equals(c.expectedTables())) {
                passed.add(c.id());
            } else {
                failed.add(c.id() + " expected=" + c.expectedTables() + " actual=" + actual);
            }
        }

        assertThat(failed)
                .as("selector recall failures:\n" + String.join("\n", failed))
                .isEmpty();
    }

    private static String descriptionFor(String qualified) {
        return switch (qualified) {
            case "public.customers" -> "Customer records";
            case "public.orders" -> "Customer orders";
            case "public.t1" -> "Table one";
            case "public.t2" -> "Table two";
            case "public.t3" -> "Table three";
            case "public.b_table" -> "B table";
            case "public.a_table" -> "A table";
            default -> null;
        };
    }

    private record RecallFixtures(String version, String generatedAt, List<RecallCase> cases) {
    }

    private record RecallCase(
            String id,
            String question,
            List<String> candidates,
            Integer maxTables,
            List<String> expectedTables
    ) {
        public RecallCase {
            if (candidates == null || candidates.isEmpty()) {
                candidates = List.of(
                        "public.customers", "public.orders", "public.t1", "public.t2", "public.t3",
                        "public.b_table", "public.a_table", "pg_catalog.pg_class"
                );
            }
        }
    }
}
