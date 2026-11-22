package com.claimdesk.claims.api.dto;

import jakarta.validation.constraints.*;

/**
 * ASCII-only email: see V5__email_ascii.sql / UserRepository#findByNormalisedEmail.
 * Password capped at 128: bcrypt only reads 72 bytes, and an unbounded field is free CPU/memory for an attacker.
 */
public record LoginRequest(
        @NotBlank @Email @Size(max = 200) @Pattern(regexp = "^[!-~]+$", message = "must be printable ASCII") String email,
        @NotBlank @Size(max = 128) String password) {}
