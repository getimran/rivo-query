package com.knowimran.rivoquery.querypolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.catalog.AuthorizedCatalogSlice;
import com.knowimran.rivoquery.catalog.CanonicalQualifiedName;
import com.knowimran.rivoquery.catalog.RawQualifiedName;
import com.knowimran.rivoquery.catalog.SchemaObject;
import com.knowimran.rivoquery.catalog.SchemaObjectType;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.lifecycle.CanonicalIdGenerator;
import com.knowimran.rivoquery.connection.application.lifecycle.SecureRandomCanonicalIdGenerator;
import com.knowimran.rivoquery.connection.QueryRequestId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuerySentryTest {

    static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    static final ConnectionId CONNECTION = new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFY");
    static final CanonicalIdGenerator ID_GEN = new SecureRandomCanonicalIdGenerator();

    QuerySentry sentry() {
        return new QuerySentry(new JsqlParserSqlParser(), "querysentry-1", ID_GEN);
    }

    AuthorizedCatalogSlice catalog(List<SchemaObject> objects) {
        return new AuthorizedCatalogSlice(
                WORKSPACE, CONNECTION, "fingerprint-v1", objects,
                100, "postgresql", List.of("COUNT"));
    }

    static SchemaObject obj(String schema, String table) {
        return new SchemaObject(
                new RawQualifiedName(schema, table),
                new CanonicalQualifiedName(schema, table),
                SchemaObjectType.TABLE,
                null, List.of(), false, false);
    }

    ProposedQuery ready(String sql) {
        return new ProposedQuery(sql, ProposedQuery.AmbiguityStatus.READY, 95, List.of());
    }

    QuerySentry.QueryPolicy policy() {
        return new QuerySentry.QueryPolicy(50, 200, 10, 5, 1000, 5000, 30);
    }

    @Test
    void approvesSimpleSelect() throws Exception {
        var slice = catalog(List.of(obj("public", "users")));
        var approved = sentry().evaluate(ready("SELECT COUNT(*) FROM public.users"), slice, DatabaseKind.POSTGRESQL, policy());

        assertThat(approved.queryId()).isInstanceOf(QueryRequestId.class);
        assertThat(approved.canonicalSql()).contains("COUNT(*)");
        assertThat(approved.referencedObjects()).contains("public.users");
        assertThat(approved.risks()).isEmpty();
        assertThat(approved.enforcedLimits())
                .containsEntry("maxRows", 100)
                .containsEntry("maxTables", 1);
    }

    @Test
    void rejectsProposalNotReady() {
        var slice = catalog(List.of());
        assertThatThrownBy(() -> sentry().evaluate(
                new ProposedQuery("N/A", ProposedQuery.AmbiguityStatus.NEEDS_CLARIFICATION, 50, List.of()),
                slice, DatabaseKind.POSTGRESQL, policy()))
                .isInstanceOf(QuerySentry.QueryPolicyException.class)
                .hasMessageContaining("not ready");
    }

    @Test
    void rejectsDml() {
        var slice = catalog(List.of(obj("public", "users")));
        assertThatThrownBy(() -> sentry().evaluate(
                ready("UPDATE public.users SET name = 'x' WHERE id = 1"), slice, DatabaseKind.POSTGRESQL, policy()))
                .isInstanceOf(QuerySentry.QueryPolicyException.class);
    }

    @Test
    void rejectsDdl() {
        var slice = catalog(List.of(obj("public", "users")));
        assertThatThrownBy(() -> sentry().evaluate(
                ready("DROP TABLE public.users"), slice, DatabaseKind.POSTGRESQL, policy()))
                .isInstanceOf(QuerySentry.QueryPolicyException.class);
    }

    @Test
    void rejectsStackedStatements() {
        var slice = catalog(List.of(obj("public", "users")));
        assertThatThrownBy(() -> sentry().evaluate(
                ready("SELECT 1; DROP TABLE public.users"), slice, DatabaseKind.POSTGRESQL, policy()))
                .isInstanceOf(QuerySentry.QueryPolicyException.class);
    }

    @Test
    void rejectsCrossSchemaAccess() {
        var slice = catalog(List.of(obj("public", "users")));
        assertThatThrownBy(() -> sentry().evaluate(
                ready("SELECT COUNT(*) FROM other_schema.users"), slice, DatabaseKind.POSTGRESQL, policy()))
                .isInstanceOf(QuerySentry.QueryPolicyException.class);
    }

    @Test
    void rejectsUnrecognizedObject() {
        var slice = catalog(List.of(obj("public", "users")));
        assertThatThrownBy(() -> sentry().evaluate(
                ready("SELECT COUNT(*) FROM public.nonexistent_table"), slice, DatabaseKind.POSTGRESQL, policy()))
                .isInstanceOf(QuerySentry.QueryPolicyException.class);
    }

    @Test
    void rejectsTooManyTables() {
        var slice = catalog(List.of(
                obj("public", "users"),
                obj("public", "orders"),
                obj("public", "products")));
        var tightPolicy = new QuerySentry.QueryPolicy(2, 200, 10, 5, 1000, 5000, 30);
        assertThatThrownBy(() -> sentry().evaluate(
                ready("SELECT COUNT(*) FROM public.users, public.orders, public.products"), slice, DatabaseKind.POSTGRESQL, tightPolicy))
                .isInstanceOf(QuerySentry.QueryPolicyException.class);
    }

    @Test
    void enforcesStrictestRowLimit() throws Exception {
        var slice = catalog(List.of(obj("public", "users")));
        var approved = sentry().evaluate(ready("SELECT * FROM public.users"), slice, DatabaseKind.POSTGRESQL,
                new QuerySentry.QueryPolicy(50, 200, 10, 5, 50, 5000, 30));

        assertThat(approved.enforcedLimits().get("maxRows")).isEqualTo(50);
    }

    @Test
    void acceptsMixedCaseWhenAuthorized() throws Exception {
        var slice = catalog(List.of(obj("public", "users")));
        var approved = sentry().evaluate(ready("SELECT COUNT(*) FROM public.Users"), slice, DatabaseKind.POSTGRESQL, policy());

        assertThat(approved.queryId()).isInstanceOf(QueryRequestId.class);
        assertThat(approved.referencedObjects()).contains("public.Users");
    }

    @Test
    void generatesValidQueryRequestId() throws Exception {
        var slice = catalog(List.of(obj("public", "users")));
        var approved = sentry().evaluate(ready("SELECT 1 FROM public.users"), slice, DatabaseKind.POSTGRESQL, policy());

        assertThat(approved.queryId().value())
                .matches("^qry_[0-9A-HJKMNP-TV-Z]{26}$");
    }

    @Test
    void expiresAfterTtl() throws Exception {
        var slice = catalog(List.of(obj("public", "users")));
        var approved = sentry().evaluate(ready("SELECT 1"), slice, DatabaseKind.POSTGRESQL,
                new QuerySentry.QueryPolicy(50, 200, 10, 5, 1000, 5000, 1));

        assertThat(approved.isExpired()).isFalse();
    }
}
