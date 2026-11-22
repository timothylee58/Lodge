package com.claimdesk.claims.infrastructure;

import com.claimdesk.claims.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<AppUser, UUID> {
    /**
     * Exact match on the normalised (ASCII lower-case) email. Deliberately NOT findByEmailIgnoreCase: that compiles to
     * upper(email) = upper(?), and Postgres upper() folds some non-ASCII letters (dotless i -> I), letting a different
     * spelling reach the same account under a different login-throttle key.
     */
    @Query("select u from AppUser u where lower(u.email) = :email")
    Optional<AppUser> findByNormalisedEmail(@Param("email") String normalisedEmail);
}
