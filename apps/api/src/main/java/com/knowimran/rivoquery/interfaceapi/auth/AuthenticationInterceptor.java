package com.knowimran.rivoquery.interfaceapi.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs authentication before any handler method (and therefore before any
 * workspace-header parsing, authorization, or resource lookup - PD2-003 step
 * 1). Registered only for {@code /api/v1/**} minus {@code /api/v1/health/**}
 * (see {@link WebMvcConfig}), matching the OpenAPI contract's {@code
 * security: []} override on the two health operations.
 *
 * <p>Thrown as a plain exception (not written directly to the response) so
 * {@code SafeProblemHandler}'s existing {@code @ExceptionHandler} machinery
 * produces the response - {@code HandlerExceptionResolver} runs for
 * exceptions thrown from {@code preHandle}, so this integrates without any
 * parallel error-writing path.
 */
public final class AuthenticationInterceptor implements HandlerInterceptor {

    public static final String PRINCIPAL_ATTRIBUTE = AuthenticationInterceptor.class.getName() + ".principal";

    private final RequestAuthenticator authenticator;

    public AuthenticationInterceptor(RequestAuthenticator authenticator) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AuthenticatedPrincipal principal = authenticator.authenticate(request);
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        return true;
    }

    public static AuthenticatedPrincipal currentPrincipal(HttpServletRequest request) {
        Object attribute = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        if (!(attribute instanceof AuthenticatedPrincipal principal)) {
            throw new AuthenticationFailedException();
        }
        return principal;
    }
}
