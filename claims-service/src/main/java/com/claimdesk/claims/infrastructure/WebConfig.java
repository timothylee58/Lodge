package com.claimdesk.claims.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final LoginRateLimiter loginRateLimiter;

    public WebConfig(LoginRateLimiter loginRateLimiter) { this.loginRateLimiter = loginRateLimiter; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginRateLimiter).addPathPatterns("/api/auth/login");
    }
}
