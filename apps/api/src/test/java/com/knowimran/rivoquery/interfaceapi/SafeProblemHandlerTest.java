package com.knowimran.rivoquery.interfaceapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowimran.rivoquery.connection.InvalidConnectionTransitionException;
import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.connection.StaleConnectionVersionException;
import com.knowimran.rivoquery.connection.application.ConnectionAuditCategory;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionEndpointNotYetPersistedException;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionLifecycleFailedException;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionNotFoundException;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectorTestFailedException;
import com.knowimran.rivoquery.connection.application.lifecycle.IndeterminateLifecycleOutcomeException;
import com.knowimran.rivoquery.connection.application.lifecycle.PreActionAuditWriteFailedException;
import com.knowimran.rivoquery.connection.connector.ConnectorOutcomeCode;
import com.knowimran.rivoquery.identity.AuthorizationDeniedException;
import com.knowimran.rivoquery.interfaceapi.auth.AuthenticationFailedException;
import com.knowimran.rivoquery.interfaceapi.connection.ConnectionEnableNotSupportedException;
import com.knowimran.rivoquery.interfaceapi.connection.ConnectionEndpointUpdateNotSupportedException;
import com.knowimran.rivoquery.interfaceapi.operation.OperationNotFoundException;
import com.knowimran.rivoquery.interfaceapi.permission.MembershipNotEligibleException;
import com.knowimran.rivoquery.interfaceapi.permission.PermissionCollectionVersionConflictException;
import com.knowimran.rivoquery.interfaceapi.support.IdempotencyKeyReusedException;
import com.knowimran.rivoquery.interfaceapi.support.ValidationFailedException;
import com.knowimran.rivoquery.interfaceapi.support.WorkspaceAccessDeniedException;
import com.knowimran.rivoquery.interfaceapi.support.WorkspaceSelectorInvalidException;
import com.knowimran.rivoquery.interfaceapi.support.WorkspaceSelectorNotAllowedException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every stable exception the {@code interfaceapi} layer can throw or let
 * escape must map to the RFC 9457 status/code the OpenAPI {@code
 * ProblemDetails.code} catalog (or this handler's own documented extension
 * of it) promises, and must never leak the underlying cause's message.
 */
class SafeProblemHandlerTest {

    private static final String SECRET = "do-not-expose-this-driver-detail";
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var validator = new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingStubController())
                .setControllerAdvice(new SafeProblemHandler())
                .setValidator(validator)
                .addFilters(new RequestIdFilter())
                .build();
    }

    static Stream<Arguments> scenarios() {
        return Stream.of(
                Arguments.of("authentication-failed", 401, "AUTHENTICATION_REQUIRED"),
                Arguments.of("workspace-selector-invalid", 400, "WORKSPACE_SELECTOR_INVALID"),
                Arguments.of("workspace-selector-not-allowed", 400, "WORKSPACE_SELECTOR_NOT_ALLOWED"),
                Arguments.of("workspace-access-denied", 403, "WORKSPACE_ACCESS_DENIED"),
                Arguments.of("authorization-denied", 403, "ADMINISTRATOR_REQUIRED"),
                Arguments.of("connection-not-found", 404, "CONNECTION_NOT_FOUND"),
                Arguments.of("operation-not-found", 404, "OPERATION_NOT_FOUND"),
                Arguments.of("membership-not-eligible", 404, "MEMBERSHIP_NOT_FOUND"),
                Arguments.of("validation-failed", 400, "VALIDATION_FAILED"),
                Arguments.of("constraint-violation", 400, "VALIDATION_FAILED"),
                Arguments.of("idempotency-key-reused", 409, "IDEMPOTENCY_KEY_REUSED"),
                Arguments.of("stale-connection-version", 412, "PRECONDITION_FAILED"),
                Arguments.of("permission-collection-conflict", 412, "PRECONDITION_FAILED"),
                Arguments.of("invalid-connection-transition", 409, "CONNECTION_STATE_CONFLICT"),
                Arguments.of("connector-test-failed", 422, "CONNECTION_TEST_FAILED"),
                Arguments.of("connection-endpoint-not-yet-persisted", 503, "CONNECTION_TEST_NOT_YET_SUPPORTED"),
                Arguments.of("connection-enable-not-supported", 503, "CONNECTION_ENABLE_NOT_YET_SUPPORTED"),
                Arguments.of("connection-endpoint-update-not-supported", 400, "CONNECTION_ENDPOINT_UPDATE_NOT_YET_SUPPORTED"),
                Arguments.of("indeterminate-lifecycle-outcome", 500, "INTERNAL_ERROR"),
                Arguments.of("pre-action-audit-write-failed", 500, "INTERNAL_ERROR"),
                Arguments.of("lifecycle-failed-authorization", 403, "ADMINISTRATOR_REQUIRED"),
                Arguments.of("lifecycle-failed-validation", 400, "VALIDATION_FAILED"),
                Arguments.of("lifecycle-failed-destination-policy", 422, "DESTINATION_NOT_ALLOWED"),
                Arguments.of("lifecycle-failed-secret-provider", 503, "SECRET_PROVIDER_UNAVAILABLE"),
                Arguments.of("lifecycle-failed-connector", 422, "CONNECTION_TEST_FAILED"),
                Arguments.of("lifecycle-failed-concurrency", 409, "CONNECTION_STATE_CONFLICT"),
                Arguments.of("lifecycle-failed-internal", 500, "INTERNAL_ERROR"));
    }

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @MethodSource("scenarios")
    void mapsToTheStableSafeProblem(String scenario, int status, String code) throws Exception {
        mockMvc.perform(get("/test/throw/" + scenario).accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().is(status))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SECRET))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("RuntimeException"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("stackTrace"))));
    }

    @Test
    void missingWorkspaceHeaderMapsToWorkspaceSelectorInvalid() throws Exception {
        mockMvc.perform(post("/test/headers")
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .header("If-Match", "\"1\"")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORKSPACE_SELECTOR_INVALID"));
    }

    @Test
    void missingIdempotencyKeyHeaderMapsToItsOwnRequiredCode() throws Exception {
        mockMvc.perform(post("/test/headers")
                        .header("X-RivoQuery-Workspace-Id", "wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX")
                        .header("If-Match", "\"1\"")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void missingIfMatchHeaderMapsToPreconditionRequired() throws Exception {
        mockMvc.perform(post("/test/headers")
                        .header("X-RivoQuery-Workspace-Id", "wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX")
                        .header("Idempotency-Key", "idem-key-1234567890abcdef")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
    }

    // HandlerMethodValidationException (a constraint violation on a plain
    // @RequestParam/@RequestHeader/@PathVariable, e.g. the generated
    // ConnectionsApi's @Min/@Max/@Pattern-annotated parameters) is mapped by
    // the same handler as MethodArgumentNotValidException below and is
    // exercised end to end by OpenApiContractTest's contract assertions
    // plus real Spring Boot method-validation autoconfiguration - it is not
    // independently reproduced here because MockMvcBuilders.standaloneSetup
    // does not activate Spring's method-validation interceptor outside a
    // full ApplicationContext/@EnableWebMvc, unlike @Valid @RequestBody
    // validation (still exercised below) which standalone setup does honor.

    @Test
    void invalidRequestBodyMapsToValidationFailed() throws Exception {
        mockMvc.perform(post("/test/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_PROBLEM_JSON)
                        .content("{\"value\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void malformedJsonBodyMapsToValidationFailed() throws Exception {
        mockMvc.perform(post("/test/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_PROBLEM_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unsupportedRequestMediaTypeIsRejected() throws Exception {
        mockMvc.perform(post("/test/body")
                        .contentType(MediaType.APPLICATION_XML)
                        .accept(MediaType.APPLICATION_PROBLEM_JSON)
                        .content("<value/>"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @RestController
    @Validated
    private static final class ThrowingStubController {

        @GetMapping("/test/throw/{scenario}")
        String throwScenario(@PathVariable String scenario) {
            throw switch (scenario) {
                case "authentication-failed" -> new AuthenticationFailedException();
                case "workspace-selector-invalid" -> new WorkspaceSelectorInvalidException();
                case "workspace-selector-not-allowed" -> new WorkspaceSelectorNotAllowedException();
                case "workspace-access-denied" -> new WorkspaceAccessDeniedException();
                case "authorization-denied" -> new AuthorizationDeniedException();
                case "connection-not-found" -> new ConnectionNotFoundException();
                case "operation-not-found" -> new OperationNotFoundException();
                case "membership-not-eligible" -> new MembershipNotEligibleException();
                case "validation-failed" -> new ValidationFailedException("bad request");
                case "constraint-violation" -> new ConstraintViolationException(java.util.Set.of());
                case "idempotency-key-reused" -> new IdempotencyKeyReusedException();
                case "stale-connection-version" -> new StaleConnectionVersionException();
                case "permission-collection-conflict" -> new PermissionCollectionVersionConflictException();
                case "invalid-connection-transition" -> new InvalidConnectionTransitionException();
                case "connector-test-failed" -> new ConnectorTestFailedException(ConnectorOutcomeCode.CONNECTION_TEST_FAILED);
                case "connection-endpoint-not-yet-persisted" -> new ConnectionEndpointNotYetPersistedException();
                case "connection-enable-not-supported" -> new ConnectionEnableNotSupportedException();
                case "connection-endpoint-update-not-supported" -> new ConnectionEndpointUpdateNotSupportedException();
                case "indeterminate-lifecycle-outcome" ->
                        new IndeterminateLifecycleOutcomeException(new OperationId("op_01J6MZ4H9ST5N7YQ2C8W3V0KFX"));
                case "pre-action-audit-write-failed" -> new PreActionAuditWriteFailedException();
                case "lifecycle-failed-authorization" -> lifecycleFailure(ConnectionAuditCategory.AUTHORIZATION);
                case "lifecycle-failed-validation" -> lifecycleFailure(ConnectionAuditCategory.VALIDATION);
                case "lifecycle-failed-destination-policy" -> lifecycleFailure(ConnectionAuditCategory.DESTINATION_POLICY);
                case "lifecycle-failed-secret-provider" -> lifecycleFailure(ConnectionAuditCategory.SECRET_PROVIDER);
                case "lifecycle-failed-connector" -> lifecycleFailure(ConnectionAuditCategory.CONNECTOR);
                case "lifecycle-failed-concurrency" -> lifecycleFailure(ConnectionAuditCategory.CONCURRENCY);
                case "lifecycle-failed-internal" -> lifecycleFailure(ConnectionAuditCategory.INTERNAL);
                default -> new IllegalStateException("unknown scenario: " + scenario);
            };
        }

        private static ConnectionLifecycleFailedException lifecycleFailure(ConnectionAuditCategory category) {
            return new ConnectionLifecycleFailedException(
                    category, new RuntimeException(SECRET));
        }

        @PostMapping("/test/headers")
        String headers(
                @RequestHeader("X-RivoQuery-Workspace-Id") String workspaceId,
                @RequestHeader("Idempotency-Key") String idempotencyKey,
                @RequestHeader("If-Match") String ifMatch) {
            return "ok";
        }

        @GetMapping("/test/validated")
        String validated(@RequestParam @Min(1) int limit) {
            return "ok";
        }

        @PostMapping("/test/body")
        String body(@Valid @RequestBody SampleBody body) {
            return "ok";
        }
    }

    private record SampleBody(@NotBlank String value) {}
}
