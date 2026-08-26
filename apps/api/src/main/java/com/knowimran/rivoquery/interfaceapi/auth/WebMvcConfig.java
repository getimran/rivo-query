package com.knowimran.rivoquery.interfaceapi.auth;

import java.util.Objects;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RequestAuthenticator authenticator;

    public WebMvcConfig(RequestAuthenticator authenticator) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthenticationInterceptor(authenticator))
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/health/**");
    }
}
