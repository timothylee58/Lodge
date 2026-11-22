package com.claimdesk.claims.infrastructure;

import com.claimdesk.claims.application.TooManyAttemptsException;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {
    static class MutableClock extends Clock {
        long now = 1_000_000;
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }

    @Test
    void blocksAfterLimitPerKeyAndRecoversWhenTheWindowRolls() {
        var clock = new MutableClock();
        var limiter = new LoginRateLimiter(3, clock);
        for (int i = 0; i < 3; i++) limiter.check("1.1.1.1");
        assertThatThrownBy(() -> limiter.check("1.1.1.1")).isInstanceOf(TooManyAttemptsException.class)
                .satisfies(e -> assertThat(((TooManyAttemptsException) e).retryAfterSeconds()).isBetween(1L, 60L));
        limiter.check("2.2.2.2"); // other clients unaffected

        clock.now += 60_001;
        limiter.check("1.1.1.1"); // new window
    }
}
