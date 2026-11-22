package com.lodge.claims.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.Locale;

/**
 * Per-account lockout: N consecutive failures inside the window lock the account for a fixed time.
 * State lives in Postgres so it holds across instances and restarts. Counting is a single atomic upsert,
 * so parallel guesses cannot slip past the counter (they can only race the check, bounded by the per-IP limit).
 * While locked, even the CORRECT password is refused: otherwise a guesser simply keeps going.
 */
@Service
public class LoginThrottle {
    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    // Old-row values (the SET clause of ON CONFLICT sees the pre-update row): restart the count if the lock
    // has expired or the last failure is older than the window.
    private static final String NEXT_COUNT = "CASE WHEN (login_throttle.locked_until <= now() "
            + "OR login_throttle.updated_at < now() - make_interval(secs => :window)) "
            + "THEN 1 ELSE login_throttle.failed_attempts + 1 END";
    private static final String RECORD_FAILURE = """
            INSERT INTO login_throttle (email, failed_attempts, locked_until, updated_at)
            VALUES (:email, 1, CASE WHEN 1 >= :max THEN now() + make_interval(secs => :lock) END, now())
            ON CONFLICT (email) DO UPDATE SET
              failed_attempts = %1$s,
              locked_until = CASE WHEN %1$s >= :max THEN now() + make_interval(secs => :lock) END,
              updated_at = now()
            """.formatted(NEXT_COUNT);

    private final NamedParameterJdbcTemplate jdbc;
    private final int maxFailures;
    private final long lockSeconds;
    private final long windowSeconds;

    public LoginThrottle(NamedParameterJdbcTemplate jdbc,
                         @Value("${lodge.auth.max-failures:5}") int maxFailures,
                         @Value("${lodge.auth.lock-minutes:15}") long lockMinutes) {
        this.jdbc = jdbc;
        this.maxFailures = maxFailures;
        this.lockSeconds = lockMinutes * 60;
        this.windowSeconds = lockMinutes * 60; // failures older than one lock period no longer count
    }

    public static String normalise(String email) {
        var e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return e.length() > 200 ? e.substring(0, 200) : e;
    }

    public static boolean isAscii(String s) {
        return s.chars().allMatch(c -> c >= 0x21 && c <= 0x7E);
    }

    /** @throws TooManyAttemptsException with the remaining lock time */
    public void assertNotLocked(String email) {
        var remaining = jdbc.queryForList("""
                SELECT CEIL(EXTRACT(EPOCH FROM (locked_until - now())))::bigint
                FROM login_throttle WHERE email = :email AND locked_until > now()""",
                new MapSqlParameterSource("email", email), Long.class);
        if (!remaining.isEmpty()) throw new TooManyAttemptsException(remaining.get(0));
    }

    public void recordFailure(String email) {
        jdbc.update(RECORD_FAILURE, new MapSqlParameterSource().addValue("email", email)
                .addValue("max", maxFailures).addValue("lock", lockSeconds).addValue("window", windowSeconds));
    }

    public void recordSuccess(String email) {
        jdbc.update("DELETE FROM login_throttle WHERE email = :email", new MapSqlParameterSource("email", email));
    }

    /** Bounds table growth from spray attacks with random emails. */
    @Scheduled(cron = "0 23 * * * *")
    public void prune() {
        int n = jdbc.getJdbcTemplate().update("DELETE FROM login_throttle WHERE updated_at < now() - interval '1 day' "
                + "AND (locked_until IS NULL OR locked_until < now())");
        if (n > 0) log.info("Pruned {} stale login_throttle rows", n);
    }
}
