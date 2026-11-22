package com.claimdesk.claims.application;

import com.claimdesk.claims.domain.AppUser;
import com.claimdesk.claims.infrastructure.JwtService;
import com.claimdesk.claims.infrastructure.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    // Real hash of a throwaway value so unknown-email and wrong-password cost the same (no enumeration by timing).
    private final String dummyHash;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users; this.encoder = encoder; this.jwt = jwt;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    public record Result(String token, long expiresInSeconds, AppUser user) {}

    public Result login(String email, String password) {
        var user = users.findByEmailIgnoreCase(email).orElse(null);
        boolean ok = encoder.matches(password, user == null ? dummyHash : user.getPassword());
        if (user == null || !ok) throw new BadCredentialsException("Invalid email or password");
        return new Result(jwt.issue(user), jwt.ttlSeconds(), user);
    }
}
