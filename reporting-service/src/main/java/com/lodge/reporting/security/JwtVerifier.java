package com.lodge.reporting.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Verifies tokens issued by claims-service (shared HS256 secret). Production path: OIDC/JWKS. */
@Component
public class JwtVerifier {
    public record Caller(String subject, String role) {}
    private final SecretKey key;

    public JwtVerifier(@Value("${reporting.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<Caller> verify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            var role = c.get("role", String.class);
            return role == null ? Optional.empty() : Optional.of(new Caller(c.getSubject(), role));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
