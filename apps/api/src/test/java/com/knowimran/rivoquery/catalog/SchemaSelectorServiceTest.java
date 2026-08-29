package com.knowimran.rivoquery.catalog;

import com.knowimran.rivoquery.catalog.application.SchemaSelectorPort;
import com.knowimran.rivoquery.catalog.application.SchemaSelectorService;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaSelectorServiceTest {
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final ConnectionId CONNECTION = new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX");

    @Test
    void exactNameMatchScoresHighest() {
        SchemaObject table = object("public", "customers", "Customer records", List.of(
                column("id", "Primary key"),
                column("name", "Full name")
        ));
        SchemaSelectorService selector = new SchemaSelectorService();
        SchemaObjectPage result = selector.selectRelevantObjects(
                WORKSPACE, CONNECTION, "customers", List.of(table), policy(10, 50, 200, 2, 4000)
        );
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).canonicalName().qualified()).isEqualTo("public.customers");
    }

    @Test
    void descriptionOverlapScoresObject() {
        SchemaObject table = object("public", "orders", "Customer orders", List.of(
                column("id", "Primary key"),
                column("customer_id", "Foreign key to customers")
        ));
        SchemaSelectorService selector = new SchemaSelectorService();
        SchemaObjectPage result = selector.selectRelevantObjects(
                WORKSPACE, CONNECTION, "customer orders", List.of(table), policy(10, 50, 200, 2, 4000)
        );
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void systemObjectsAreExcluded() {
        SchemaObject systemTable = object("pg_catalog", "pg_class", "System catalog", List.of(
                column("oid", "Object identifier")
        ), true);
        SchemaSelectorService selector = new SchemaSelectorService();
        SchemaObjectPage result = selector.selectRelevantObjects(
                WORKSPACE, CONNECTION, "class", List.of(systemTable), policy(10, 50, 200, 2, 4000)
        );
        assertThat(result.items()).isEmpty();
    }

    @Test
    void maxTablesBudgetEnforced() {
        SchemaObject t1 = object("public", "t1", "Table one", List.of(column("id", "PK")));
        SchemaObject t2 = object("public", "t2", "Table two", List.of(column("id", "PK")));
        SchemaObject t3 = object("public", "t3", "Table three", List.of(column("id", "PK")));
        SchemaSelectorService selector = new SchemaSelectorService();
        SchemaObjectPage result = selector.selectRelevantObjects(
                WORKSPACE, CONNECTION, "table", List.of(t1, t2, t3), policy(2, 50, 200, 2, 4000)
        );
        assertThat(result.items()).hasSize(2);
    }

    @Test
    void maxTotalColumnsBudgetEnforced() {
        SchemaObject t1 = object("public", "t1", "Table one", List.of(
                column("c1", "Column 1"),
                column("c2", "Column 2")
        ));
        SchemaObject t2 = object("public", "t2", "Table two", List.of(
                column("c1", "Column 1"),
                column("c2", "Column 2"),
                column("c3", "Column 3")
        ));
        SchemaSelectorService selector = new SchemaSelectorService();
        SchemaObjectPage result = selector.selectRelevantObjects(
                WORKSPACE, CONNECTION, "table", List.of(t1, t2), policy(10, 50, 3, 2, 4000)
        );
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void tiesBrokenByCanonicalNameLexicographicOrder() {
        SchemaObject t1 = object("public", "b_table", "B table", List.of(column("id", "PK")));
        SchemaObject t2 = object("public", "a_table", "A table", List.of(column("id", "PK")));
        SchemaSelectorService selector = new SchemaSelectorService();
        SchemaObjectPage result = selector.selectRelevantObjects(
                WORKSPACE, CONNECTION, "table", List.of(t1, t2), policy(10, 50, 200, 2, 4000)
        );
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).canonicalName().qualified()).isEqualTo("public.a_table");
        assertThat(result.items().get(1).canonicalName().qualified()).isEqualTo("public.b_table");
    }

    private static SchemaObject object(String schema, String name, String description, List<SchemaColumn> columns, boolean systemObject) {
        return new SchemaObject(
                new RawQualifiedName(schema, name),
                new CanonicalQualifiedName(schema, name),
                SchemaObjectType.TABLE,
                description,
                columns,
                systemObject,
                false
        );
    }

    private static SchemaObject object(String schema, String name, String description, List<SchemaColumn> columns) {
        return object(schema, name, description, columns, false);
    }

    private static SchemaColumn column(String name, String description) {
        return new SchemaColumn(name, ColumnTypeFamily.STRING, true, description, ColumnClassification.NONE);
    }

    private static SchemaSelectorPort.SelectionPolicy policy(int maxTables, int maxColumnsPerTable, int maxTotalColumns, int maxHops, int maxTokens) {
        return new SchemaSelectorPort.SelectionPolicy(maxTables, maxColumnsPerTable, maxTotalColumns, maxHops, maxTokens);
    }
}
