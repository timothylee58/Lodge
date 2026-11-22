package com.claimdesk.claims.infrastructure;

import com.claimdesk.claims.domain.AppUser;
import com.claimdesk.claims.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtService {
    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${claimdesk.jwt.secret}") String secret,
                      @Value("${claimdesk.jwt.ttl-minutes}") long ttlMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); // throws if < 256 bits
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public String issue(AppUser u) {
        var now = Instant.now();
        return Jwts.builder().subject(u.getId().toString())
                .claim("role", u.getRole().name()).claim("name", u.getName())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(ttl)))
                .signWith(key).compact();
    }

    /** Empty for any invalid/expired/forged token. */
    public Optional<AuthenticatedUser> verify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return Optional.of(new AuthenticatedUser(UUID.fromString(c.getSubject()),
                    Role.valueOf(c.get("role", String.class)), c.get("name", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long ttlSeconds() { return ttl.toSeconds(); }
}
