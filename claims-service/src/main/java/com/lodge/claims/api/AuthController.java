package com.lodge.claims.api;

import com.lodge.claims.api.dto.*;
import com.lodge.claims.application.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        var r = auth.login(req.email(), req.password());
        return new LoginResponse(r.token(), r.expiresInSeconds(), r.user().getId(), r.user().getName(),
                r.user().getRole().name());
    }
}
