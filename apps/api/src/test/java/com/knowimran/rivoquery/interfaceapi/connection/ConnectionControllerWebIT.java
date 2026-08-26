package com.knowimran.rivoquery.interfaceapi.connection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowimran.rivoquery.platform.persistence.ControlDatabaseRoleFixture;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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

/**
 * S5 fix: {@code MockMvcBuilders.standaloneSetup} (every other {@code
 * ConnectionController}/{@code WorkspaceController}/{@code
 * ConnectionPermissionController} test) never activates Spring MVC's real
 * method-validation interceptor, so the actually-shipped production
 * behavior for a malformed/duplicate {@code X-RivoQuery-Workspace-Id} header
 * and an out-of-range {@code limit} query parameter - both declared via
 * {@code @Pattern}/{@code @Min}/{@code @Max} on the generated {@code
 * ConnectionsApi} interface - was previously unproven end to end. This is
 * the one full-{@code ApplicationContext} test for {@code
 * interfaceapi.connection} (follows {@code HealthEndpointIT}'s established
 * Testcontainers/full-context pattern - the only other one in this repo -
 * rather than inventing a second one).
 *
 * <p>The JWT minted here is a minimal, hand-rolled HS256 token matching
 * exactly what {@code JwtBearerAuthenticator} verifies (no external JWT
 * library is used in production either - see that class's own Javadoc).
 * Authentication must succeed for these tests to reach Spring's own
 * argument-resolution/validation stage at all (interceptors run before
 * argument binding), but no actor/membership rows are seeded: every
 * scenario here is rejected by parameter validation before the controller
 * body - and therefore before any membership lookup - ever runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "debug=false")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ConnectionControllerWebIT {

    private static final String JWT_ISSUER = "https://it-test.invalid/issuer";
    private static final String JWT_AUDIENCE = "rivoquery-it-test";
    private static final String JWT_HMAC_SECRET_BASE64 = "8UiYe9QgQVrG93BIC4EmxASGt+O1k3PUcs0A2TLCoZg=";

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:17.6-alpine3.22@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    private static final String RUNTIME_APP_USERNAME = "rivoquery_app_it";
    private static final String RUNTIME_APP_PASSWORD = "rivoquery-app-it-synthetic-pw";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        ControlDatabaseRoleFixture.createRuntimeRole(POSTGRES, RUNTIME_APP_USERNAME, RUNTIME_APP_PASSWORD);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_APP_USERNAME);
        registry.add("spring.datasource.password", () -> RUNTIME_APP_PASSWORD);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        // rivoquery.auth.jwt.* is a plain @ConfigurationProperties bean, so
        // (unlike RIVOQUERY_SECRETS_LOCAL_*/RIVOQUERY_SECRETS_PROVIDER, which
        // read raw System.getenv() and must come from the failsafe plugin's
        // own environmentVariables - see HealthEndpointIT's identical note)
        // this can be overridden here directly.
        registry.add("rivoquery.auth.jwt.issuer", () -> JWT_ISSUER);
        registry.add("rivoquery.auth.jwt.audience", () -> JWT_AUDIENCE);
        registry.add("rivoquery.auth.jwt.hmac-secret-base64", () -> JWT_HMAC_SECRET_BASE64);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aMalformedWorkspaceHeaderTriggersRealHandlerMethodValidationMappedToValidationFailed() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(mintToken("subject-malformed-header"));
        headers.set("X-RivoQuery-Workspace-Id", "not-a-canonical-workspace-id");
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_PROBLEM_JSON));

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/connections", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    /**
     * An exact-duplicate {@code X-RivoQuery-Workspace-Id} header is rejected
     * with {@code 400 VALIDATION_FAILED} in a real web context, before the
     * controller body runs. This corrects an earlier, wrong assumption that
     * Spring's single-valued {@code @RequestHeader String} binding silently
     * takes only the first of two identical values: the generated {@code
     * ConnectionsApi} interface is itself {@code @Validated}, so the
     * interface-level {@code @NotNull @Pattern} method validation fires on the
     * (non-matching, effectively doubled) header value and throws {@code
     * jakarta.validation.ConstraintViolationException} before {@code
     * WorkspaceHeaderResolver}'s manual raw-request duplicate check is ever
     * reached. That manual check remains defense-in-depth and is still
     * exercised (with its more specific {@code WORKSPACE_SELECTOR_INVALID}
     * code) by the standalone {@code WorkspaceHeaderResolverTest}/{@code
     * ConnectionControllerTest}, which do not activate method validation.
     */
    @Test
    void anExactDuplicateWorkspaceHeaderIsRejectedByRealMethodValidationAsValidationFailed() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(mintToken("subject-duplicate-header"));
        headers.put("X-RivoQuery-Workspace-Id",
                java.util.List.of("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX", "wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"));
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_PROBLEM_JSON));

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/connections", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void aLimitAboveTheDeclaredMaximumTriggersRealMaxValidationInsteadOfAnInternalServerError() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(mintToken("subject-limit-101"));
        headers.set("X-RivoQuery-Workspace-Id", "wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_PROBLEM_JSON));

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/connections?limit=101", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void aLimitBelowTheDeclaredMinimumTriggersRealMinValidationInsteadOfAnInternalServerError() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(mintToken("subject-limit-0"));
        headers.set("X-RivoQuery-Workspace-Id", "wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_PROBLEM_JSON));

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/connections?limit=0", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    /** Hand-rolled HS256 JWT - see class Javadoc for why. */
    private static String mintToken(String subject) throws NoSuchAlgorithmException, InvalidKeyException {
        ObjectMapper mapper = new ObjectMapper();
        String header = encode(mapper.valueToTree(Map.of("alg", "HS256", "typ", "JWT")).toString());
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = Map.of(
                "iss", JWT_ISSUER,
                "aud", JWT_AUDIENCE,
                "sub", subject,
                "iat", now,
                "exp", now + 300);
        String payload = encode(mapper.valueToTree(claims).toString());
        String signingInput = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(JWT_HMAC_SECRET_BASE64), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        return signingInput + "." + signature;
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
