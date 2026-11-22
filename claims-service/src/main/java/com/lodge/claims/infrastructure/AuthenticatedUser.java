package com.lodge.claims.infrastructure;

import com.lodge.claims.domain.Role;
import java.util.UUID;

/** Principal placed in the SecurityContext from a verified JWT. */
public record AuthenticatedUser(UUID id, Role role, String name) {}
