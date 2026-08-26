package com.knowimran.rivoquery.interfaceapi;

import com.knowimran.rivoquery.interfaceapi.auth.AuthenticatedPrincipal;
import com.knowimran.rivoquery.interfaceapi.auth.AuthenticationFailedException;
import com.knowimran.rivoquery.interfaceapi.auth.AuthenticationInterceptor;
import com.knowimran.rivoquery.interfaceapi.auth.RequestAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Shared MockMvc test scaffolding for every {@code interfaceapi} controller
 * test: a fake bearer-token {@link RequestAuthenticator} (fails closed
 * exactly like the real {@code JwtBearerAuthenticator} when the {@code
 * Authorization} header is absent) plus its matching {@link
 * AuthenticationInterceptor}, so every controller test exercises the exact
 * same auth-before-load ordering the real app gets from {@code WebMvcConfig}
 * (the interceptor's {@code preHandle} always runs before Spring MVC binds
 * a handler method's own required-header parameters) - a standalone {@code
 * MockMvc} that skipped this interceptor would let a missing {@code
 * Idempotency-Key}/{@code X-RivoQuery-Workspace-Id}/{@code If-Match} header
 * shadow a missing bearer token, producing a wrong (400/428) status for an
 * unauthenticated request instead of 401.
 */
public final class InterfaceApiTestSupport {
    public static final AuthenticatedPrincipal PRINCIPAL = new AuthenticatedPrincipal("issuer", "subject");

    private InterfaceApiTestSupport() {}

    public static AuthenticationInterceptor authenticationInterceptor() {
        return new AuthenticationInterceptor(new FakeRequestAuthenticator());
    }

    /** Adds the {@code Authorization} header the fake authenticator requires. */
    public static RequestPostProcessor authenticated() {
        return request -> {
            request.addHeader("Authorization", "Bearer test-token");
            return request;
        };
    }

    /**
     * A stand-in for the request-scoped {@link HttpServletRequest} proxy
     * Spring injects automatically into a singleton bean in a real web
     * {@code ApplicationContext} (which standalone {@code MockMvc} does not
     * provide): every method call is forwarded to whichever request {@code
     * TestDispatcherServlet} currently has bound to {@link
     * RequestContextHolder}, exactly like the real proxy resolves per call.
     */
    public static HttpServletRequest requestScopedProxy() {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> {
                    var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
                    return method.invoke(attributes.getRequest(), args);
                });
    }

    /**
     * Every {@code interfaceapi} controller constructor-injects {@code
     * ObjectProvider<HttpServletRequest>} rather than {@code
     * HttpServletRequest} directly (see e.g. {@code
     * OperationController}'s own Javadoc), so standalone MockMvc test
     * wiring needs to supply the same shape rather than the raw proxy.
     */
    public static ObjectProvider<HttpServletRequest> requestProvider() {
        return new ObjectProvider<>() {
            @Override
            public HttpServletRequest getObject() {
                return requestScopedProxy();
            }

            @Override
            public HttpServletRequest getObject(Object... args) {
                return requestScopedProxy();
            }

            @Override
            public HttpServletRequest getIfAvailable() {
                return requestScopedProxy();
            }

            @Override
            public HttpServletRequest getIfUnique() {
                return requestScopedProxy();
            }
        };
    }

    private static final class FakeRequestAuthenticator implements RequestAuthenticator {
        @Override
        public AuthenticatedPrincipal authenticate(HttpServletRequest request) {
            String header = request.getHeader("Authorization");
            if (header == null || header.isBlank()) {
                throw new AuthenticationFailedException();
            }
            return PRINCIPAL;
        }
    }
}
