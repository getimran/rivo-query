package com.knowimran.rivoquery.interfaceapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OpenApiContractTest {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "patch", "delete", "options", "head", "trace");
    private static final Set<String> HEALTH_PATHS =
            Set.of("/api/v1/health/live", "/api/v1/health/ready");

    @Test
    void onlyHealthOperationsOverrideGlobalBearerAuthentication() throws IOException {
        Map<String, Object> contract = loadContract();

        assertThat(contract.get("security")).isEqualTo(List.of(Map.of("BearerAuth", List.of())));
        assertThat(map(contract.get("paths")).keySet()).containsAll(HEALTH_PATHS);

        for (DeclaredOperation declared : declaredOperations(contract)) {
            if (HEALTH_PATHS.contains(declared.path())) {
                assertThat(declared.operation().get("security"))
                        .as("security override for %s %s", declared.method(), declared.path())
                        .isEqualTo(List.of());
            } else {
                assertThat(declared.operation())
                        .as("authenticated operation %s %s", declared.method(), declared.path())
                        .doesNotContainKey("security");
            }
        }
    }

    @Test
    void phaseOneHealthSurfaceAndHeadersRemainCompatible() throws IOException {
        Map<String, Object> contract = loadContract();
        Map<String, Object> paths = map(contract.get("paths"));

        assertThat(map(map(paths.get("/api/v1/health/live")).get("get")).get("operationId"))
                .isEqualTo("getLiveness");
        assertThat(map(map(map(paths.get("/api/v1/health/live")).get("get")).get("responses")).keySet())
                .containsExactlyInAnyOrder("200", "406", "500");
        assertThat(map(map(paths.get("/api/v1/health/ready")).get("get")).get("operationId"))
                .isEqualTo("getReadiness");
        assertThat(map(map(map(paths.get("/api/v1/health/ready")).get("get")).get("responses")).keySet())
                .containsExactlyInAnyOrder("200", "406", "500", "503");
        Map<String, Object> liveness = map(map(paths.get("/api/v1/health/live")).get("get"));
        Map<String, Object> readiness = map(map(paths.get("/api/v1/health/ready")).get("get"));
        assertThat(responseDescription(contract, liveness, "406"))
                .isEqualTo("The requested response media type is not supported.");
        assertThat(responseDescription(contract, liveness, "500"))
                .isEqualTo("An unexpected internal error occurred.");
        assertThat(responseDescription(contract, readiness, "503"))
                .isEqualTo("The control database cannot currently satisfy readiness checks.");

        for (DeclaredOperation declared : declaredOperations(contract)) {
            if (!HEALTH_PATHS.contains(declared.path())) {
                continue;
            }
            for (Object value : map(declared.operation().get("responses")).values()) {
                Map<String, Object> response = resolveResponse(contract, map(value));
                assertThat(map(response.get("headers")))
                        .as("health response headers for %s", declared.path())
                        .containsKey(RequestIdFilter.HEADER_NAME)
                        .doesNotContainKey("Cache-Control");
            }
        }
    }

    @Test
    void everyDeclaredResponseCarriesTheRequestIdHeader() throws IOException {
        Map<String, Object> contract = loadContract();

        for (DeclaredOperation declared : declaredOperations(contract)) {
            for (Map.Entry<String, Object> responseEntry :
                    map(declared.operation().get("responses")).entrySet()) {
                Map<String, Object> response = resolveResponse(contract, map(responseEntry.getValue()));
                assertThat(map(response.get("headers")))
                        .as("response %s for %s %s", responseEntry.getKey(), declared.method(), declared.path())
                        .containsKey(RequestIdFilter.HEADER_NAME);
            }
        }
    }

    @Test
    void workspaceSelectionIdempotencyAndPreconditionsArePinned() throws IOException {
        Map<String, Object> contract = loadContract();
        Set<String> preconditionedMutations = Set.of(
                "enableConnection",
                "disableConnection",
                "testConnection",
                "rotateConnectionSecret",
                "replaceConnectionPermission",
                "revokeConnectionPermission",
                "updateConnection",
                "deleteConnection");

        Set<String> nonMutatingPosts = Set.of(
                "createQueryPreview",
                "executeQuery",
                "submitQueryFeedback");

        Map<String, Object> workspaceParameter =
                map(map(map(contract.get("components")).get("parameters")).get("WorkspaceId"));
        assertThat(workspaceParameter.get("required")).isEqualTo(true);
        assertThat((String) workspaceParameter.get("description"))
                .contains("Exactly one canonical value", "never trimmed", "membership lookup");

        for (DeclaredOperation declared : declaredOperations(contract)) {
            Set<String> parameters = parameterNames(contract, declared);
            boolean health = HEALTH_PATHS.contains(declared.path());
            boolean discovery = declared.path().equals("/api/v1/workspaces");
            if (health || discovery) {
                assertThat(parameters)
                        .as("unscoped parameters for %s %s", declared.method(), declared.path())
                        .doesNotContain("X-RivoQuery-Workspace-Id");
            } else {
                assertThat(parameters)
                        .as("workspace selector for %s %s", declared.method(), declared.path())
                        .contains("X-RivoQuery-Workspace-Id");
                assertThat(map(declared.operation().get("responses")))
                        .as("non-enumerating workspace denial for %s %s", declared.method(), declared.path())
                        .containsKey("403");
            }

            if (Set.of("post", "put", "patch", "delete").contains(declared.method())
                    && !nonMutatingPosts.contains(declared.operation().get("operationId"))) {
                assertThat(parameters)
                        .as("idempotency for %s", declared.operation().get("operationId"))
                        .contains("Idempotency-Key");
            }

            String operationId = (String) declared.operation().get("operationId");
            if (preconditionedMutations.contains(operationId)) {
                assertThat(parameters).as("precondition for %s", operationId).contains("If-Match");
            }

            if (declared.path().contains("{connectionId}")) {
                assertThat(map(declared.operation().get("responses")))
                        .as("non-enumerating connection response for %s", operationId)
                        .containsKey("404");
            }
        }

        Map<String, Object> paths = map(contract.get("paths"));
        assertThat((String) map(map(paths.get("/api/v1/workspaces")).get("get")).get("description"))
                .contains("WORKSPACE_SELECTOR_NOT_ALLOWED");
        assertThat((String) map(map(paths.get("/api/v1/operations/{operationId}")).get("get"))
                        .get("description"))
                .contains("Unknown, foreign, or inaccessible IDs");
    }

    @Test
    void permissionPaginationAndCollectionConcurrencyAreBounded() throws IOException {
        Map<String, Object> contract = loadContract();
        Map<String, Object> components = map(contract.get("components"));
        Map<String, Object> schemas = map(components.get("schemas"));
        Map<String, Object> parameters = map(components.get("parameters"));
        Map<String, Object> paths = map(contract.get("paths"));

        Map<String, Object> cursor = map(schemas.get("Cursor"));
        assertThat(cursor).containsEntry("minLength", 16).containsEntry("maxLength", 256);
        Map<String, Object> limit = map(map(parameters.get("Limit")).get("schema"));
        assertThat(limit)
                .containsEntry("minimum", 1)
                .containsEntry("maximum", 100)
                .containsEntry("default", 50);

        DeclaredOperation permissionList = new DeclaredOperation(
                "/api/v1/connections/{connectionId}/permissions",
                "get",
                map(paths.get("/api/v1/connections/{connectionId}/permissions")),
                map(map(paths.get("/api/v1/connections/{connectionId}/permissions")).get("get")));
        assertThat(parameterNames(contract, permissionList)).contains("cursor", "limit");

        Map<String, Object> collection = map(schemas.get("PermissionCollection"));
        Map<String, Object> properties = map(collection.get("properties"));
        assertThat(map(properties.get("items"))).containsEntry("maxItems", 100);
        assertThat(map(properties.get("nextCursor")))
                .containsEntry("$ref", "#/components/schemas/Cursor");

        Map<String, Object> success = map(map(permissionList.operation().get("responses")).get("200"));
        assertThat(map(success.get("headers"))).containsKey("ETag");
        assertThat(map(map(components.get("responses")).get("PermissionCollectionChanged")).get("headers"))
                .satisfies(headers -> assertThat(map(headers)).containsKey("ETag"));
    }

    @Test
    void sensitiveAndUnsafeConnectionInputsStayClosedAndWriteOnly() throws IOException {
        Map<String, Object> contract = loadContract();
        Map<String, Object> schemas = map(map(contract.get("components")).get("schemas"));
        Set<String> propertyNames = new LinkedHashSet<>();
        collectPropertyNames(schemas, propertyNames);

        assertThat(propertyNames)
                .doesNotContain("jdbcUrl", "driverClass", "socketFactory", "filePath", "driverProperties");
        Map<String, Object> connectorOptions = map(schemas.get("ConnectorOptions"));
        assertThat(connectorOptions).containsEntry("additionalProperties", false);
        assertThat(map(connectorOptions.get("properties")).keySet()).containsExactly("tcpKeepAlive");

        Map<String, Object> secret = map(schemas.get("SecretInput"));
        assertThat(secret).containsEntry("writeOnly", true).containsEntry("additionalProperties", false);
        assertPasswordWriteOnly(map(secret.get("properties")), "username", "password");

        Map<String, Object> tls = map(schemas.get("TlsInput"));
        assertPasswordWriteOnly(
                map(tls.get("properties")),
                "caCertificatePem",
                "clientCertificatePem",
                "clientPrivateKeyPem");

        Map<String, Object> rotation = map(schemas.get("RotateSecretRequest"));
        assertThat(rotation).containsEntry("writeOnly", true).containsEntry("additionalProperties", false);
        assertPasswordWriteOnly(
                map(rotation.get("properties")),
                "caCertificatePem",
                "clientCertificatePem",
                "clientPrivateKeyPem");

        assertThat(stringList(map(schemas.get("GrantablePermission")).get("enum")))
                .containsExactlyInAnyOrder("VIEW_SCHEMA", "QUERY", "EXPORT")
                .doesNotContain("MANAGE");
    }

    @Test
    void generatedJavaValidationGapsAndPermissionFilteredLinksStayExplicit() throws IOException {
        Map<String, Object> contract = loadContract();
        Map<String, Object> schemas = map(map(contract.get("components")).get("schemas"));

        for (String schemaName : List.of("TlsInput", "RotateSecretRequest")) {
            Map<String, Object> schema = map(schemas.get(schemaName));
            Map<String, Object> dependentRequired = map(schema.get("dependentRequired"));
            assertThat(stringList(dependentRequired.get("clientCertificatePem")))
                    .containsExactly("clientPrivateKeyPem");
            assertThat(stringList(dependentRequired.get("clientPrivateKeyPem")))
                    .containsExactly("clientCertificatePem");
            assertThat(stringList(schema.get("x-rivoquery-runtime-validation")))
                    .containsExactly("CLIENT_CERTIFICATE_KEY_PAIR");
        }

        Map<String, Object> update = map(schemas.get("UpdateConnectionRequest"));
        assertThat(update).containsEntry("minProperties", 1);
        assertThat(stringList(update.get("x-rivoquery-runtime-validation")))
                .containsExactly("NON_EMPTY_PATCH");

        Map<String, Object> summary = map(schemas.get("ConnectionSummary"));
        assertThat(stringList(summary.get("required"))).doesNotContain("links");
        Map<String, Object> links = map(schemas.get("ConnectionLinks"));
        assertThat(links).doesNotContainKey("required");
        assertThat((String) links.get("description"))
                .contains("only when the caller may perform that action", "omitted from member summaries");
    }

    @Test
    void contractPinsRequestIdHealthProblemAndOpaqueIdShapes() throws IOException {
        Map<String, Object> contract = loadContract();
        Map<String, Object> schemas = map(map(contract.get("components")).get("schemas"));
        Map<String, Object> requestId = map(schemas.get("RequestId"));

        assertThat(requestId)
                .containsEntry("minLength", 1)
                .containsEntry("maxLength", 64)
                .containsEntry("pattern", "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
        assertThat(map(map(schemas.get("HealthResponse")).get("properties")).get("status"))
                .satisfies(status -> assertThat(map(status).get("enum")).isEqualTo(List.of("UP")));
        assertThat(stringList(map(schemas.get("ProblemDetails")).get("required")))
                .contains("type", "title", "status", "detail", "instance", "code", "requestId");

        assertThat(map(schemas.get("WorkspaceId")).get("pattern")).isEqualTo("^wsp_[0-9A-HJKMNP-TV-Z]{26}$");
        assertThat(map(schemas.get("ConnectionId")).get("pattern")).isEqualTo("^con_[0-9A-HJKMNP-TV-Z]{26}$");
        assertThat(map(schemas.get("MembershipId")).get("pattern")).isEqualTo("^mem_[0-9A-HJKMNP-TV-Z]{26}$");
        assertThat(map(schemas.get("OperationId")).get("pattern")).isEqualTo("^op_[0-9A-HJKMNP-TV-Z]{26}$");
        assertThat(map(schemas.get("IdempotencyKey")))
                .containsEntry("minLength", 16)
                .containsEntry("maxLength", 128);
    }

    private void assertPasswordWriteOnly(Map<String, Object> properties, String... names) {
        for (String name : names) {
            assertThat(map(properties.get(name)))
                    .as("sensitive property %s", name)
                    .containsEntry("format", "password")
                    .containsEntry("writeOnly", true);
        }
    }

    private List<DeclaredOperation> declaredOperations(Map<String, Object> contract) {
        List<DeclaredOperation> result = new ArrayList<>();
        for (Map.Entry<String, Object> pathEntry : map(contract.get("paths")).entrySet()) {
            Map<String, Object> pathItem = map(pathEntry.getValue());
            for (String method : HTTP_METHODS) {
                if (pathItem.containsKey(method)) {
                    result.add(new DeclaredOperation(
                            pathEntry.getKey(), method, pathItem, map(pathItem.get(method))));
                }
            }
        }
        return result;
    }

    private Set<String> parameterNames(Map<String, Object> contract, DeclaredOperation declared) {
        Set<String> result = new LinkedHashSet<>();
        addParameterNames(contract, declared.pathItem().get("parameters"), result);
        addParameterNames(contract, declared.operation().get("parameters"), result);
        return result;
    }

    private void addParameterNames(Map<String, Object> contract, Object values, Set<String> names) {
        if (values == null) {
            return;
        }
        Map<String, Object> componentParameters =
                map(map(contract.get("components")).get("parameters"));
        for (Object value : objectList(values)) {
            Map<String, Object> parameter = map(value);
            if (parameter.containsKey("$ref")) {
                String reference = (String) parameter.get("$ref");
                parameter = map(componentParameters.get(reference.substring(reference.lastIndexOf('/') + 1)));
            }
            names.add((String) parameter.get("name"));
        }
    }

    private Map<String, Object> resolveResponse(
            Map<String, Object> contract, Map<String, Object> response) {
        Map<String, Object> componentResponses =
                map(map(contract.get("components")).get("responses"));
        while (response.containsKey("$ref")) {
            String reference = (String) response.get("$ref");
            assertThat(reference).startsWith("#/components/responses/");
            response = map(componentResponses.get(reference.substring(reference.lastIndexOf('/') + 1)));
        }
        return response;
    }

    private String responseDescription(
            Map<String, Object> contract, Map<String, Object> operation, String status) {
        Map<String, Object> response = map(map(operation.get("responses")).get(status));
        return (String) resolveResponse(contract, response).get("description");
    }

    private void collectPropertyNames(Object node, Set<String> names) {
        if (node instanceof Map<?, ?> values) {
            Object properties = values.get("properties");
            if (properties instanceof Map<?, ?> propertyMap) {
                propertyMap.keySet().forEach(key -> names.add((String) key));
            }
            values.values().forEach(value -> collectPropertyNames(value, names));
        } else if (node instanceof List<?> values) {
            values.forEach(value -> collectPropertyNames(value, names));
        }
    }

    private Map<String, Object> loadContract() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/openapi/rivoquery-v1.yaml")) {
            assertThat(input).isNotNull();
            return map(new Yaml().load(input));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> objectList(Object value) {
        return (List<Object>) value;
    }

    private record DeclaredOperation(
            String path, String method, Map<String, Object> pathItem, Map<String, Object> operation) {}
}
