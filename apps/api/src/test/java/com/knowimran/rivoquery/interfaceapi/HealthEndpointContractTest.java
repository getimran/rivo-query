package com.knowimran.rivoquery.interfaceapi;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowimran.rivoquery.application.health.ControlDatabaseUnavailableException;
import com.knowimran.rivoquery.application.health.ReadinessCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HealthEndpointContractTest {

    private static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";
    private static final String SECRET = "jdbc:postgresql://db/private?password=do-not-expose";

    private StubReadinessCheck readinessCheck;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        readinessCheck = new StubReadinessCheck();
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(readinessCheck))
                .setControllerAdvice(new SafeProblemHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void livenessMatchesTheGeneratedPublicContractAndEchoesAValidRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/health/live")
                        .header(RequestIdFilter.HEADER_NAME, "caller-request.1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, "caller-request.1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"status\":\"UP\"}", true));
    }

    @Test
    void readinessReturnsUpAfterTheProbeSucceeds() throws Exception {
        mockMvc.perform(get("/api/v1/health/ready").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        RequestIdFilter.HEADER_NAME, matchesPattern(REQUEST_ID_PATTERN)))
                .andExpect(content().json("{\"status\":\"UP\"}", true));
    }

    @Test
    void invalidRequestIdIsReplacedBeforeGeneratedValidationRuns() throws Exception {
        mockMvc.perform(get("/api/v1/health/live")
                        .header(RequestIdFilter.HEADER_NAME, "invalid request id"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        RequestIdFilter.HEADER_NAME, matchesPattern(REQUEST_ID_PATTERN)))
                .andExpect(header().string(
                        RequestIdFilter.HEADER_NAME, not("invalid request id")));
    }

    @Test
    void databaseFailureReturnsSafeRfc9457ProblemDetails() throws Exception {
        readinessCheck.failure =
                new ControlDatabaseUnavailableException(new IllegalStateException(SECRET));

        mockMvc.perform(get("/api/v1/health/ready")
                        .header(RequestIdFilter.HEADER_NAME, "db-failure-request")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, "db-failure-request"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type")
                        .value("urn:rivoquery:problem:control-database-unavailable"))
                .andExpect(jsonPath("$.title").value("Control database unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value(
                        "The control database cannot currently satisfy readiness checks."))
                .andExpect(jsonPath("$.instance").value("/api/v1/health/ready"))
                .andExpect(jsonPath("$.code").value("CONTROL_DATABASE_UNAVAILABLE"))
                .andExpect(jsonPath("$.requestId").value("db-failure-request"))
                .andExpect(content().string(not(containsString(SECRET))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    @Test
    void unexpectedFailureReturnsOnlyTheStableInternalError() throws Exception {
        readinessCheck.failure =
                new IllegalStateException("org.postgresql.util.PSQLException: " + SECRET);

        mockMvc.perform(get("/api/v1/health/ready")
                        .header(RequestIdFilter.HEADER_NAME, "unexpected-request")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:rivoquery:problem:internal-error"))
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected internal error occurred."))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.requestId").value("unexpected-request"))
                .andExpect(content().string(not(containsString(SECRET))))
                .andExpect(content().string(not(containsString("PSQLException"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    @Test
    void unsupportedMethodUsesTheSafeGlobalProblemBaseline() throws Exception {
        mockMvc.perform(post("/api/v1/health/live")
                        .header(RequestIdFilter.HEADER_NAME, "wrong-method")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, "wrong-method"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.requestId").value("wrong-method"));
    }

    @Test
    void unsupportedResponseMediaTypeReturnsSafeNotAcceptableProblem() throws Exception {
        mockMvc.perform(get("/api/v1/health/live")
                        .header(RequestIdFilter.HEADER_NAME, "not-acceptable")
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, "not-acceptable"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:rivoquery:problem:not-acceptable"))
                .andExpect(jsonPath("$.title").value("Not acceptable"))
                .andExpect(jsonPath("$.status").value(406))
                .andExpect(jsonPath("$.detail")
                        .value("The requested response media type is not supported."))
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"))
                .andExpect(jsonPath("$.requestId").value("not-acceptable"))
                .andExpect(content().string(not(containsString("HttpMediaTypeNotAcceptableException"))))
                .andExpect(content().string(not(containsString("application/xml"))));
    }

    private static final class StubReadinessCheck implements ReadinessCheck {

        private RuntimeException failure;

        @Override
        public void verifyReady() {
            if (failure != null) {
                throw failure;
            }
        }
    }
}
