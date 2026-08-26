package com.knowimran.rivoquery.interfaceapi.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Validates the incoming request's bearer token and returns the caller's
 * validated external identity. See {@link JwtBearerAuthenticator} for the
 * production implementation.
 */
public interface RequestAuthenticator {
    AuthenticatedPrincipal authenticate(HttpServletRequest request);
}
