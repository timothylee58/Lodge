package com.claimdesk.claims.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    // URL rules are the coarse gate (role). Ownership ("is this MY claim / assigned to me") is
    // enforced in the services, because it depends on data, not just the role.
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter) throws Exception {
        return http.csrf(c -> c.disable())   // stateless bearer-token API, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        // Set the status directly. The default handler uses sendError(), which forwards to the
                        // (unauthenticated) /error page and turns a 403 into a 401 on a real servlet container.
                        .accessDeniedHandler((req, res, ex) -> res.setStatus(HttpStatus.FORBIDDEN.value())))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/login", "/actuator/health", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/staff/**").hasAnyRole("OFFICER", "MANAGER")
                        .requestMatchers("/api/staff/**").hasRole("OFFICER")
                        .requestMatchers(HttpMethod.POST, "/api/claims").hasRole("CLAIMANT")
                        .requestMatchers(HttpMethod.GET, "/api/claims").hasRole("CLAIMANT")
                        .requestMatchers("/api/claims/*/info-responses", "/api/policies/mine").hasRole("CLAIMANT")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
