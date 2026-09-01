package com.knowimran.rivoquery.interfaceapi;

import com.knowimran.rivoquery.application.health.ControlDatabaseUnavailableException;
import com.knowimran.rivoquery.connection.InvalidConnectionTransitionException;
import com.knowimran.rivoquery.connection.StaleConnectionVersionException;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionEndpointNotYetPersistedException;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionLifecycleFailedException;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionNotFoundException;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectorTestFailedException;
import com.knowimran.rivoquery.connection.application.lifecycle.IndeterminateLifecycleOutcomeException;
import com.knowimran.rivoquery.connection.application.lifecycle.PreActionAuditWriteFailedException;
import com.knowimran.rivoquery.identity.AuthorizationDeniedException;
import com.knowimran.rivoquery.interfaceapi.auth.AuthenticationFailedException;
import com.knowimran.rivoquery.interfaceapi.connection.ConnectionEnableNotSupportedException;
import com.knowimran.rivoquery.interfaceapi.connection.ConnectionEndpointUpdateNotSupportedException;
import com.knowimran.rivoquery.interfaceapi.schema.SchemaAccessDeniedException;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshFailedException;
import com.knowimran.rivoquery.interfaceapi.query.QueryAccessDeniedException;
import com.knowimran.rivoquery.interfaceapi.query.QueryExecutionNotImplementedException;
import com.knowimran.rivoquery.interfaceapi.query.QueryFeedbackNotImplementedException;
import com.knowimran.rivoquery.interfaceapi.query.QueryPolicyViolationException;
import com.knowimran.rivoquery.interfaceapi.query.QueryProviderException;
import com.knowimran.rivoquery.interfaceapi.query.QuerySchemaStaleException;
import com.knowimran.rivoquery.interfaceapi.generated.model.ProblemDetails;
import com.knowimran.rivoquery.interfaceapi.operation.OperationNotFoundException;
import com.knowimran.rivoquery.interfaceapi.permission.MembershipNotEligibleException;
import com.knowimran.rivoquery.interfaceapi.permission.PermissionCollectionVersionConflictException;
import com.knowimran.rivoquery.interfaceapi.support.IdempotencyKeyInFlightException;
import com.knowimran.rivoquery.interfaceapi.support.IdempotencyKeyReusedException;
import com.knowimran.rivoquery.interfaceapi.support.ValidationFailedException;
import com.knowimran.rivoquery.interfaceapi.support.WorkspaceAccessDeniedException;
import com.knowimran.rivoquery.interfaceapi.support.WorkspaceSelectorInvalidException;
import com.knowimran.rivoquery.interfaceapi.support.WorkspaceSelectorNotAllowedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Central RFC 9457 mapping for every stable, safe exception type the
 * {@code interfaceapi} layer can throw or let escape, plus the Spring MVC
 * plumbing exceptions generated-interface parameter binding/validation can
 * raise before a controller method body ever runs. The {@code code} value on
 * every response matches (or, where the fixed catalog in the OpenAPI {@code
 * ProblemDetails.code} description has no exact entry for a genuinely
 * distinct failure shape, is a clearly-named, pattern-valid extension of) the
 * stable code catalog documented there. No branch below ever forwards a
 * caught exception's own message, cause, or stack trace into the response
 * body - every {@code detail} string is a fixed, hand-written, safe constant.
 */
@RestControllerAdvice
public final class SafeProblemHandler {

    private static final URI CONTROL_DATABASE_UNAVAILABLE_TYPE =
            URI.create("urn:rivoquery:problem:control-database-unavailable");
    private static final URI INTERNAL_ERROR_TYPE = URI.create("urn:rivoquery:problem:internal-error");
    private static final URI NOT_FOUND_TYPE = URI.create("urn:rivoquery:problem:not-found");
    private static final URI METHOD_NOT_ALLOWED_TYPE =
            URI.create("urn:rivoquery:problem:method-not-allowed");
    private static final URI NOT_ACCEPTABLE_TYPE =
            URI.create("urn:rivoquery:problem:not-acceptable");
    private static final URI UNSUPPORTED_MEDIA_TYPE_TYPE =
            URI.create("urn:rivoquery:problem:unsupported-media-type");
    private static final URI VALIDATION_FAILED_TYPE = URI.create("urn:rivoquery:problem:validation-failed");
    private static final URI AUTHENTICATION_REQUIRED_TYPE =
            URI.create("urn:rivoquery:problem:authentication-required");
    private static final URI WORKSPACE_SELECTOR_INVALID_TYPE =
            URI.create("urn:rivoquery:problem:workspace-selector-invalid");
    private static final URI WORKSPACE_SELECTOR_NOT_ALLOWED_TYPE =
            URI.create("urn:rivoquery:problem:workspace-selector-not-allowed");
    private static final URI WORKSPACE_ACCESS_DENIED_TYPE =
            URI.create("urn:rivoquery:problem:workspace-access-denied");
    private static final URI ADMINISTRATOR_REQUIRED_TYPE =
            URI.create("urn:rivoquery:problem:administrator-required");
    private static final URI CONNECTION_NOT_FOUND_TYPE = URI.create("urn:rivoquery:problem:connection-not-found");
    private static final URI OPERATION_NOT_FOUND_TYPE = URI.create("urn:rivoquery:problem:operation-not-found");
    private static final URI MEMBERSHIP_NOT_FOUND_TYPE = URI.create("urn:rivoquery:problem:membership-not-found");
    private static final URI PRECONDITION_REQUIRED_TYPE =
            URI.create("urn:rivoquery:problem:precondition-required");
    private static final URI PRECONDITION_FAILED_TYPE = URI.create("urn:rivoquery:problem:precondition-failed");
    private static final URI IDEMPOTENCY_KEY_REQUIRED_TYPE =
            URI.create("urn:rivoquery:problem:idempotency-key-required");
    private static final URI IDEMPOTENCY_KEY_REUSED_TYPE =
            URI.create("urn:rivoquery:problem:idempotency-key-reused");
    private static final URI IDEMPOTENCY_KEY_IN_FLIGHT_TYPE =
            URI.create("urn:rivoquery:problem:idempotency-key-in-flight");
    private static final URI CONNECTION_STATE_CONFLICT_TYPE =
            URI.create("urn:rivoquery:problem:connection-state-conflict");
    private static final URI DESTINATION_NOT_ALLOWED_TYPE =
            URI.create("urn:rivoquery:problem:destination-not-allowed");
    private static final URI SECRET_PROVIDER_UNAVAILABLE_TYPE =
            URI.create("urn:rivoquery:problem:secret-provider-unavailable");
    private static final URI CONNECTION_TEST_FAILED_TYPE =
            URI.create("urn:rivoquery:problem:connection-test-failed");
    private static final URI CONNECTION_TEST_NOT_YET_SUPPORTED_TYPE =
            URI.create("urn:rivoquery:problem:connection-test-not-yet-supported");
    private static final URI CONNECTION_ENABLE_NOT_YET_SUPPORTED_TYPE =
            URI.create("urn:rivoquery:problem:connection-enable-not-yet-supported");
    private static final URI CONNECTION_ENDPOINT_UPDATE_NOT_YET_SUPPORTED_TYPE =
            URI.create("urn:rivoquery:problem:connection-endpoint-update-not-yet-supported");

    // ------------------------------------------------------------------
    // Pre-existing Phase 1 mappings (unchanged)
    // ------------------------------------------------------------------

    @ExceptionHandler(ControlDatabaseUnavailableException.class)
    ResponseEntity<ProblemDetails> handleControlDatabaseUnavailable(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                CONTROL_DATABASE_UNAVAILABLE_TYPE,
                "Control database unavailable",
                "The control database cannot currently satisfy readiness checks.",
                "CONTROL_DATABASE_UNAVAILABLE");
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ProblemDetails> handleNotFound(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.NOT_FOUND,
                NOT_FOUND_TYPE,
                "Resource not found",
                "The requested resource was not found.",
                "NOT_FOUND");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetails> handleMethodNotAllowed(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.METHOD_NOT_ALLOWED,
                METHOD_NOT_ALLOWED_TYPE,
                "Method not allowed",
                "The requested method is not supported for this resource.",
                "METHOD_NOT_ALLOWED");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ProblemDetails> handleNotAcceptable(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.NOT_ACCEPTABLE,
                NOT_ACCEPTABLE_TYPE,
                "Not acceptable",
                "The requested response media type is not supported.",
                "NOT_ACCEPTABLE");
    }

    // ------------------------------------------------------------------
    // Authentication / workspace selection / authorization (PD2-003)
    // ------------------------------------------------------------------

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<ProblemDetails> handleAuthenticationFailed(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.UNAUTHORIZED,
                AUTHENTICATION_REQUIRED_TYPE,
                "Authentication required",
                "A valid bearer token is required.",
                "AUTHENTICATION_REQUIRED");
    }

    @ExceptionHandler(WorkspaceSelectorInvalidException.class)
    ResponseEntity<ProblemDetails> handleWorkspaceSelectorInvalid(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.BAD_REQUEST,
                WORKSPACE_SELECTOR_INVALID_TYPE,
                "Workspace selector invalid",
                "The X-RivoQuery-Workspace-Id header is missing, duplicated, or malformed.",
                "WORKSPACE_SELECTOR_INVALID");
    }

    @ExceptionHandler(WorkspaceSelectorNotAllowedException.class)
    ResponseEntity<ProblemDetails> handleWorkspaceSelectorNotAllowed(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.BAD_REQUEST,
                WORKSPACE_SELECTOR_NOT_ALLOWED_TYPE,
                "Workspace selector not allowed",
                "This operation is unscoped and does not accept a workspace selector.",
                "WORKSPACE_SELECTOR_NOT_ALLOWED");
    }

    @ExceptionHandler(WorkspaceAccessDeniedException.class)
    ResponseEntity<ProblemDetails> handleWorkspaceAccessDenied(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.FORBIDDEN,
                WORKSPACE_ACCESS_DENIED_TYPE,
                "Workspace access denied",
                "The caller has no active membership in the selected workspace.",
                "WORKSPACE_ACCESS_DENIED");
    }

    /**
     * Every remaining {@link AuthorizationDeniedException} reaching this
     * handler originates from {@code ActorContext.requireAdministrator()}
     * (the workspace-membership-resolution case is already re-thrown as
     * {@link WorkspaceAccessDeniedException} by {@code ActorResolver} before
     * it can reach here - see that class's Javadoc), so {@code
     * ADMINISTRATOR_REQUIRED} is accurate for this handler's entire
     * remaining domain.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    ResponseEntity<ProblemDetails> handleAuthorizationDenied(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.FORBIDDEN,
                ADMINISTRATOR_REQUIRED_TYPE,
                "Administrator required",
                "This operation requires an active workspace administrator.",
                "ADMINISTRATOR_REQUIRED");
    }

    // ------------------------------------------------------------------
    // Not-found (connection / operation / permission-grant-target)
    // ------------------------------------------------------------------

    @ExceptionHandler(ConnectionNotFoundException.class)
    ResponseEntity<ProblemDetails> handleConnectionNotFound(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.NOT_FOUND,
                CONNECTION_NOT_FOUND_TYPE,
                "Connection not found",
                "The requested connection was not found.",
                "CONNECTION_NOT_FOUND");
    }

    @ExceptionHandler(OperationNotFoundException.class)
    ResponseEntity<ProblemDetails> handleOperationNotFound(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.NOT_FOUND,
                OPERATION_NOT_FOUND_TYPE,
                "Operation not found",
                "The requested operation was not found.",
                "OPERATION_NOT_FOUND");
    }

    @ExceptionHandler(MembershipNotEligibleException.class)
    ResponseEntity<ProblemDetails> handleMembershipNotEligible(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.NOT_FOUND,
                MEMBERSHIP_NOT_FOUND_TYPE,
                "Membership not found",
                "The requested membership was not found or is not an eligible grant target.",
                "MEMBERSHIP_NOT_FOUND");
    }

    // ------------------------------------------------------------------
    // Validation / idempotency / preconditions
    // ------------------------------------------------------------------

    @ExceptionHandler(ValidationFailedException.class)
    ResponseEntity<ProblemDetails> handleValidationFailed(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.BAD_REQUEST,
                VALIDATION_FAILED_TYPE,
                "Validation failed",
                "The request did not satisfy the API contract.",
                "VALIDATION_FAILED");
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HandlerMethodValidationException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class
    })
    ResponseEntity<ProblemDetails> handleGeneratedValidationFailed(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.BAD_REQUEST,
                VALIDATION_FAILED_TYPE,
                "Validation failed",
                "The request did not satisfy the API contract.",
                "VALIDATION_FAILED");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetails> handleUnsupportedMediaType(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                UNSUPPORTED_MEDIA_TYPE_TYPE,
                "Unsupported media type",
                "The request body media type is not supported.",
                "UNSUPPORTED_MEDIA_TYPE");
    }

    /**
     * Every required header on a generated operation interface method
     * (workspace selector, Idempotency-Key, If-Match) is bound as a plain
     * {@code @RequestHeader(required = true)} String parameter, so Spring
     * rejects an entirely-absent value before the controller body runs (the
     * "duplicated" and "malformed" cases still reach the controller body -
     * see {@code WorkspaceHeaderResolver}/{@code EtagCodec} - only "entirely
     * absent" is intercepted this early). Mapped per header name so the
     * client sees the same stable code an explicit check further downstream
     * would have produced.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetails> handleMissingRequestHeader(
            MissingRequestHeaderException exception, HttpServletRequest request) {
        return switch (exception.getHeaderName()) {
            case "X-RivoQuery-Workspace-Id" -> handleWorkspaceSelectorInvalid(request);
            case "Idempotency-Key" -> problem(
                    request,
                    HttpStatus.BAD_REQUEST,
                    IDEMPOTENCY_KEY_REQUIRED_TYPE,
                    "Idempotency key required",
                    "This mutation requires an Idempotency-Key header.",
                    "IDEMPOTENCY_KEY_REQUIRED");
            case "If-Match" -> problem(
                    request,
                    HttpStatus.PRECONDITION_REQUIRED,
                    PRECONDITION_REQUIRED_TYPE,
                    "Precondition required",
                    "This mutation requires an exact If-Match header.",
                    "PRECONDITION_REQUIRED");
            default -> handleValidationFailed(request);
        };
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ProblemDetails> handleIdempotencyKeyReused(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.CONFLICT,
                IDEMPOTENCY_KEY_REUSED_TYPE,
                "Idempotency key reused",
                "This Idempotency-Key was already used for a different request.",
                "IDEMPOTENCY_KEY_REUSED");
    }

    /**
     * A concurrent request is genuinely mid-flight for this exact key right
     * now (see {@code IdempotencyGuard#reserve}'s own Javadoc) - a transient
     * "retry shortly" signal, not a duplicate-mutation risk or a conflicting
     * reuse, so this is deliberately distinct from {@link
     * IdempotencyKeyReusedException}'s handler even though both map to 409.
     */
    @ExceptionHandler(IdempotencyKeyInFlightException.class)
    ResponseEntity<ProblemDetails> handleIdempotencyKeyInFlight(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.CONFLICT,
                IDEMPOTENCY_KEY_IN_FLIGHT_TYPE,
                "Idempotency key in flight",
                "A request with this Idempotency-Key is already being processed; retry shortly.",
                "IDEMPOTENCY_KEY_IN_FLIGHT");
    }

    @ExceptionHandler({StaleConnectionVersionException.class, PermissionCollectionVersionConflictException.class})
    ResponseEntity<ProblemDetails> handlePreconditionFailed(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.PRECONDITION_FAILED,
                PRECONDITION_FAILED_TYPE,
                "Precondition failed",
                "The If-Match version no longer matches the resource's current version.",
                "PRECONDITION_FAILED");
    }

    @ExceptionHandler(InvalidConnectionTransitionException.class)
    ResponseEntity<ProblemDetails> handleInvalidConnectionTransition(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.CONFLICT,
                CONNECTION_STATE_CONFLICT_TYPE,
                "Connection state conflict",
                "The connection's current state does not allow this operation.",
                "CONNECTION_STATE_CONFLICT");
    }

    // ------------------------------------------------------------------
    // Lifecycle-saga outcomes (P2-100's stable, safe exception surface)
    // ------------------------------------------------------------------

    @ExceptionHandler(ConnectorTestFailedException.class)
    ResponseEntity<ProblemDetails> handleConnectorTestFailed(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.UNPROCESSABLE_ENTITY,
                CONNECTION_TEST_FAILED_TYPE,
                "Connection test failed",
                "The connection could not be validated against the supplied configuration.",
                "CONNECTION_TEST_FAILED");
    }

    /**
     * {@code test}/{@code rotate-secret} are structurally blocked pending a
     * V3 schema migration (see that exception's own Javadoc) - a genuine,
     * stable "not yet supported" capability gap, not a transient outage, but
     * mapped to 503 (both endpoints declare it) rather than 500 so a client
     * can tell it apart from an unexpected internal failure.
     */
    @ExceptionHandler(ConnectionEndpointNotYetPersistedException.class)
    ResponseEntity<ProblemDetails> handleConnectionEndpointNotYetPersisted(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                CONNECTION_TEST_NOT_YET_SUPPORTED_TYPE,
                "Connection test not yet supported",
                "Testing or rotating this connection's secret against its own stored "
                        + "endpoint is not yet supported.",
                "CONNECTION_TEST_NOT_YET_SUPPORTED");
    }

    /**
     * {@code enable} is a genuine, deterministic, permanent capability gap
     * (see {@link ConnectionEnableNotSupportedException}'s own Javadoc), not
     * a transient failure a client should blindly retry - mapped to 503
     * (matching {@code test}/{@code rotate-secret}'s own honest "not yet
     * supported" signal) rather than 500, which would incorrectly imply the
     * next attempt might succeed.
     */
    @ExceptionHandler(ConnectionEnableNotSupportedException.class)
    ResponseEntity<ProblemDetails> handleConnectionEnableNotSupported(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                CONNECTION_ENABLE_NOT_YET_SUPPORTED_TYPE,
                "Connection enable not yet supported",
                "Re-enabling a connection is not yet supported.",
                "CONNECTION_ENABLE_NOT_YET_SUPPORTED");
    }

    /**
     * {@code PATCH} declaring a non-null {@code endpoint} is a client-side
     * usage error (the field is contractually present but not yet accepted -
     * see {@link ConnectionEndpointUpdateNotSupportedException}'s own
     * Javadoc for why), so this is 400 rather than 503: the client can
     * correct the request immediately by omitting the field, it is not a
     * capability the server might regain on retry.
     */
    @ExceptionHandler(ConnectionEndpointUpdateNotSupportedException.class)
    ResponseEntity<ProblemDetails> handleConnectionEndpointUpdateNotSupported(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.BAD_REQUEST,
                CONNECTION_ENDPOINT_UPDATE_NOT_YET_SUPPORTED_TYPE,
                "Connection endpoint update not yet supported",
                "Updating a connection's endpoint is not yet supported; omit the endpoint field.",
                "CONNECTION_ENDPOINT_UPDATE_NOT_YET_SUPPORTED");
    }

    /**
     * The outcome could not be durably confirmed after a real effect already
     * happened (see the exception's own Javadoc); its {@code operationId}
     * (when present) is intentionally never included in this response body -
     * {@code ProblemDetails} has no extension field for it, and it is
     * reconcilable server-side (via {@code GET /operations/{id}}) rather
     * than something a client needs to be handed here.
     */
    @ExceptionHandler(IndeterminateLifecycleOutcomeException.class)
    ResponseEntity<ProblemDetails> handleIndeterminateLifecycleOutcome(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_ERROR_TYPE,
                "Internal server error",
                "The outcome of this request could not be confirmed; it is safe to check the "
                        + "connection's current state before retrying.",
                "INTERNAL_ERROR");
    }

    @ExceptionHandler(PreActionAuditWriteFailedException.class)
    ResponseEntity<ProblemDetails> handlePreActionAuditWriteFailed(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_ERROR_TYPE,
                "Internal server error",
                "An unexpected internal error occurred; no action was attempted.",
                "INTERNAL_ERROR");
    }

    /**
     * {@link ConnectionLifecycleFailedException} is the P2-100 trust
     * boundary every platform-adapter failure crosses through (see its own
     * Javadoc): its {@code category()} is the only safe signal this handler
     * may use, never {@code getCause()} or {@code getMessage()} (both may
     * ultimately chain to a platform exception's own, unsafe detail).
     */
    @ExceptionHandler(ConnectionLifecycleFailedException.class)
    ResponseEntity<ProblemDetails> handleConnectionLifecycleFailed(
            ConnectionLifecycleFailedException exception, HttpServletRequest request) {
        return switch (exception.category()) {
            case AUTHORIZATION -> handleAuthorizationDenied(request);
            case VALIDATION -> handleValidationFailed(request);
            case DESTINATION_POLICY -> problem(
                    request,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    DESTINATION_NOT_ALLOWED_TYPE,
                    "Destination not allowed",
                    "The connection's destination could not be validated against policy.",
                    "DESTINATION_NOT_ALLOWED");
            case SECRET_PROVIDER -> problem(
                    request,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    SECRET_PROVIDER_UNAVAILABLE_TYPE,
                    "Secret provider unavailable",
                    "The secret provider did not complete this operation.",
                    "SECRET_PROVIDER_UNAVAILABLE");
            case CONNECTOR -> handleConnectorTestFailed(request);
            case CONCURRENCY -> handleInvalidConnectionTransition(request);
            case INTERNAL -> handleUnexpected(request);
        };
    }

    @ExceptionHandler(SchemaAccessDeniedException.class)
    ResponseEntity<ProblemDetails> handleSchemaAccessDenied(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.FORBIDDEN,
                URI.create("urn:rivoquery:problem:schema-access-denied"),
                "Schema access denied",
                "The caller is not authorized to view this connection's schema.",
                "SCHEMA_ACCESS_DENIED");
    }

    @ExceptionHandler(SchemaRefreshFailedException.class)
    ResponseEntity<ProblemDetails> handleSchemaRefreshFailed(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.BAD_GATEWAY,
                URI.create("urn:rivoquery:problem:schema-refresh-failed"),
                "Schema refresh failed",
                "The schema refresh operation could not be completed.",
                "SCHEMA_REFRESH_FAILED");
    }

    @ExceptionHandler(QueryAccessDeniedException.class)
    ResponseEntity<ProblemDetails> handleQueryAccessDenied(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.FORBIDDEN,
                URI.create("urn:rivoquery:problem:query-access-denied"),
                "Query access denied",
                "The caller is not authorized to query this connection.",
                "QUERY_ACCESS_DENIED");
    }

    @ExceptionHandler(QuerySchemaStaleException.class)
    ResponseEntity<ProblemDetails> handleQuerySchemaStale(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.CONFLICT,
                URI.create("urn:rivoquery:problem:query-schema-stale"),
                "Schema metadata unavailable",
                "No current schema snapshot is available for this connection. Refresh the schema and retry.",
                "QUERY_SCHEMA_STALE");
    }

    @ExceptionHandler(QueryProviderException.class)
    ResponseEntity<ProblemDetails> handleQueryProviderException(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                URI.create("urn:rivoquery:problem:model-provider-unavailable"),
                "Model provider unavailable",
                "The model provider could not be reached or returned an error.",
                "MODEL_PROVIDER_UNAVAILABLE");
    }

    @ExceptionHandler(QueryPolicyViolationException.class)
    ResponseEntity<ProblemDetails> handleQueryPolicyViolation(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.UNPROCESSABLE_ENTITY,
                URI.create("urn:rivoquery:problem:query-rejected"),
                "Query rejected",
                "The generated query was rejected by policy.",
                "QUERY_REJECTED");
    }

    @ExceptionHandler(QueryExecutionNotImplementedException.class)
    ResponseEntity<ProblemDetails> handleQueryExecutionNotImplemented(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.NOT_IMPLEMENTED,
                URI.create("urn:rivoquery:problem:query-execution-not-implemented"),
                "Query execution not available",
                "Query execution is not yet implemented in this version.",
                "QUERY_EXECUTION_NOT_IMPLEMENTED");
    }

    @ExceptionHandler(QueryFeedbackNotImplementedException.class)
    ResponseEntity<ProblemDetails> handleQueryFeedbackNotImplemented(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.NOT_IMPLEMENTED,
                URI.create("urn:rivoquery:problem:query-feedback-not-implemented"),
                "Query feedback not available",
                "Query feedback is not yet implemented in this version.",
                "QUERY_FEEDBACK_NOT_IMPLEMENTED");
    }

    // ------------------------------------------------------------------
    // Fallback
    // ------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetails> handleUnexpected(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_ERROR_TYPE,
                "Internal server error",
                "An unexpected internal error occurred.",
                "INTERNAL_ERROR");
    }

    private ResponseEntity<ProblemDetails> problem(
            HttpServletRequest request,
            HttpStatus status,
            URI type,
            String title,
            String detail,
            String code) {
        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        ProblemDetails body = new ProblemDetails(
                type, title, status.value(), detail, request.getRequestURI(), code, requestId);

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
