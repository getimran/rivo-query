package com.knowimran.rivoquery.interfaceapi;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sets {@code Cache-Control: private, no-store} on every {@code /api/v1/**}
 * response except the two unauthenticated health checks, matching the
 * OpenAPI contract's {@code PrivateNoStore} header requirement on every
 * workspace/connection/permission/operation response (PD2-003: "Responses
 * containing workspace data use private/no-store caching semantics").
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class CacheControlFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/v1/") && !request.getRequestURI().startsWith("/api/v1/health/")) {
            response.setHeader("Cache-Control", "private, no-store");
        }
        filterChain.doFilter(request, response);
    }
}
