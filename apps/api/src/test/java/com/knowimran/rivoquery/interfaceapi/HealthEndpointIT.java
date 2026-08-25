package com.knowimran.rivoquery.interfaceapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowimran.rivoquery.platform.persistence.ControlDatabaseRoleFixture;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "debug=false")
@Testcontainers
@TestMethodOrder(OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class HealthEndpointIT {

    private static final String SECRET_MARKER = "do-not-expose";
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:17.6-alpine3.22@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withUsername("rivoquery")
                    .withPassword(SECRET_MARKER);

    // A restricted role distinct from the container's bootstrap superuser
    // (which Flyway migrates as); see ControlDatabaseRoleFixture and V2's
    // own comments on why this split must hold and why V2 no longer creates
    // this role itself.
    private static final String RUNTIME_APP_USERNAME = "rivoquery_app_it";
    private static final String RUNTIME_APP_PASSWORD = "rivoquery-app-it-synthetic-pw";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        // The @Testcontainers extension starts static @Container fields
        // before @DynamicPropertySource methods run, so POSTGRES is already
        // reachable here.
        ControlDatabaseRoleFixture.createRuntimeRole(POSTGRES, RUNTIME_APP_USERNAME, RUNTIME_APP_PASSWORD);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_APP_USERNAME);
        registry.add("spring.datasource.password", () -> RUNTIME_APP_PASSWORD);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @Order(1)
    void liveAndReadyEndpointsRunAgainstTheMigratedControlDatabase() throws Exception {
        ResponseEntity<String> live = exchange(
                "/api/v1/health/live", HttpMethod.GET, "integration-live", MediaType.APPLICATION_JSON);
        assertThat(live.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(live.getHeaders().getFirst(RequestIdFilter.HEADER_NAME))
                .isEqualTo("integration-live");
        assertThat(objectMapper.readTree(live.getBody()).path("status").asText()).isEqualTo("UP");

        ResponseEntity<String> ready = exchange(
                "/api/v1/health/ready", HttpMethod.GET, "integration-ready", MediaType.APPLICATION_JSON);
        assertThat(ready.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ready.getHeaders().getFirst(RequestIdFilter.HEADER_NAME))
                .isEqualTo("integration-ready");
        assertThat(objectMapper.readTree(ready.getBody()).path("status").asText()).isEqualTo("UP");

        ResponseEntity<String> invalid = exchange(
                "/api/v1/health/live", HttpMethod.GET, "invalid request", MediaType.APPLICATION_JSON);
        String replacement = invalid.getHeaders().getFirst(RequestIdFilter.HEADER_NAME);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replacement)
                .isNotEqualTo("invalid request")
                .matches(RequestIdFilter.VALID_REQUEST_ID);
    }

    @Test
    @Order(2)
    void globalErrorsAreSafeAndActuatorEndpointsAreNotPublic() throws Exception {
        assertProblem(
                exchange("/missing", HttpMethod.GET, "missing-route", MediaType.APPLICATION_PROBLEM_JSON),
                HttpStatus.NOT_FOUND,
                "NOT_FOUND",
                "missing-route");
        assertProblem(
                exchange(
                        "/api/v1/health/live",
                        HttpMethod.POST,
                        "wrong-method",
                        MediaType.APPLICATION_PROBLEM_JSON),
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "wrong-method");
        assertProblem(
                exchange(
                        "/actuator/health",
                        HttpMethod.GET,
                        "hidden-actuator",
                        MediaType.APPLICATION_PROBLEM_JSON),
                HttpStatus.NOT_FOUND,
                "NOT_FOUND",
                "hidden-actuator");
        assertProblem(
                exchange(
                        "/api/v1/health/live",
                        HttpMethod.GET,
                        "not-acceptable-live",
                        MediaType.APPLICATION_XML),
                HttpStatus.NOT_ACCEPTABLE,
                "NOT_ACCEPTABLE",
                "not-acceptable-live");
    }

    @Test
    @Order(3)
    void requestIdsAreNeverMetricLabels() {
        Set<String> requestIds = Set.of("metric-request-one", "metric-request-two");
        requestIds.forEach(requestId -> exchange(
                "/api/v1/health/live", HttpMethod.GET, requestId, MediaType.APPLICATION_JSON));

        List<Meter> httpMeters = meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("http.server.requests"))
                .toList();
        assertThat(httpMeters).isNotEmpty();
        assertThat(httpMeters)
                .allSatisfy(meter -> meter.getId().getTags().forEach(tag -> {
                    assertThat(tag.getKey()).isNotEqualToIgnoringCase("requestId");
                    assertThat(tag.getKey()).isNotEqualToIgnoringCase("x-request-id");
                    assertThat(requestIds).doesNotContain(tag.getValue());
                }));
    }

    @Test
    @Order(4)
    void runtimeDatabaseFailureIsBoundedAndDoesNotAffectLiveness(CapturedOutput output)
            throws Exception {
        POSTGRES.stop();

        Instant started = Instant.now();
        ResponseEntity<String> ready = exchange(
                "/api/v1/health/ready",
                HttpMethod.GET,
                "database-down",
                MediaType.APPLICATION_PROBLEM_JSON);
        Duration elapsed = Duration.between(started, Instant.now());

        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        assertProblem(
                ready,
                HttpStatus.SERVICE_UNAVAILABLE,
                "CONTROL_DATABASE_UNAVAILABLE",
                "database-down");

        ResponseEntity<String> live = exchange(
                "/api/v1/health/live", HttpMethod.GET, "still-live", MediaType.APPLICATION_JSON);
        assertThat(live.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(live.getBody()).path("status").asText()).isEqualTo("UP");
        assertThat(output.getAll())
                .doesNotContain(
                        SECRET_MARKER,
                        "PSQLException",
                        "SQLException",
                        "stack_trace");
    }

    private ResponseEntity<String> exchange(
            String path, HttpMethod method, String requestId, MediaType accept) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(RequestIdFilter.HEADER_NAME, requestId);
        headers.setAccept(List.of(accept));
        return rest.exchange(path, method, new HttpEntity<>(headers), String.class);
    }

    private void assertProblem(
            ResponseEntity<String> response,
            HttpStatus status,
            String code,
            String requestId)
            throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getFirst(RequestIdFilter.HEADER_NAME)).isEqualTo(requestId);

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("status").asInt()).isEqualTo(status.value());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("requestId").asText()).isEqualTo(requestId);
        assertThat(response.getBody())
                .doesNotContain(SECRET_MARKER, "PSQLException", "SQLException", "stackTrace", "jdbc:");
    }
}
