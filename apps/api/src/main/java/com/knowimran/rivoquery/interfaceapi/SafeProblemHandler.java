package com.knowimran.rivoquery.interfaceapi;

import com.knowimran.rivoquery.application.health.ControlDatabaseUnavailableException;
import com.knowimran.rivoquery.interfaceapi.generated.model.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
