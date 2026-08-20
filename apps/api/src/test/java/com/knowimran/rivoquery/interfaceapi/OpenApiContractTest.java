package com.knowimran.rivoquery.interfaceapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OpenApiContractTest {

    @Test
    void contractContainsOnlyTheTwoPublicUnauthenticatedHealthOperations() throws IOException {
        Map<String, Object> contract = loadContract();
        Map<String, Object> paths = map(contract.get("paths"));

        assertThat(paths.keySet())
                .containsExactlyInAnyOrder("/api/v1/health/live", "/api/v1/health/ready");
        for (Object pathValue : paths.values()) {
            Map<String, Object> path = map(pathValue);
            assertThat(path.keySet()).containsExactly("get");
            assertThat(map(path.get("get")).get("security")).isEqualTo(List.of());
            assertThat(map(map(path.get("get")).get("responses"))).containsKey("406");
        }
    }

    @Test
    void contractPinsRequestIdHealthAndProblemDetailsShapes() throws IOException {
        Map<String, Object> contract = loadContract();
        Map<String, Object> components = map(contract.get("components"));
        Map<String, Object> schemas = map(components.get("schemas"));
        Map<String, Object> requestId = map(schemas.get("RequestId"));

        assertThat(requestId)
                .containsEntry("minLength", 1)
                .containsEntry("maxLength", 64)
                .containsEntry("pattern", "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

        Map<String, Object> healthProperties = map(map(schemas.get("HealthResponse")).get("properties"));
        assertThat(map(healthProperties.get("status")).get("enum")).isEqualTo(List.of("UP"));

        Map<String, Object> problem = map(schemas.get("ProblemDetails"));
        assertThat(list(problem.get("required")))
                .contains("type", "title", "status", "detail", "instance", "code", "requestId");
    }

    @Test
    void everyDeclaredResponseCarriesTheRequestIdHeader() throws IOException {
        Map<String, Object> contract = loadContract();
        Map<String, Object> paths = map(contract.get("paths"));
        Map<String, Object> componentResponses = map(map(contract.get("components")).get("responses"));

        for (Object pathValue : paths.values()) {
            Map<String, Object> responses = map(map(pathValue).get("get"));
            responses = map(responses.get("responses"));
            for (Object responseValue : responses.values()) {
                Map<String, Object> response = map(responseValue);
                if (response.containsKey("$ref")) {
                    String name = ((String) response.get("$ref")).substring("#/components/responses/".length());
                    response = map(componentResponses.get(name));
                }
                assertThat(map(response.get("headers"))).containsKey(RequestIdFilter.HEADER_NAME);
            }
        }
    }

    private Map<String, Object> loadContract() throws IOException {
        try (InputStream input = getClass()
                .getResourceAsStream("/openapi/rivoquery-v1.yaml")) {
            assertThat(input).isNotNull();
            return map(new Yaml().load(input));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<String> list(Object value) {
        return (List<String>) value;
    }
}
