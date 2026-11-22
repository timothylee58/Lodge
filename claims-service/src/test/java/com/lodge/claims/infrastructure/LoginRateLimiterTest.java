package com.lodge.claims.infrastructure;

import com.lodge.claims.application.TooManyAttemptsException;
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

    @Test
    void ipv6AddressesInsideOneSlash64ShareABucket() {
        assertThat(LoginRateLimiter.keyFor("2001:db8:0:0:1::1")).isEqualTo(LoginRateLimiter.keyFor("2001:db8::ffff:2:3:4"));
        assertThat(LoginRateLimiter.keyFor("2001:db8:0:1::1")).isNotEqualTo(LoginRateLimiter.keyFor("2001:db8:0:0::1"));
        assertThat(LoginRateLimiter.keyFor("203.0.113.7")).isEqualTo("203.0.113.7");

        var limiter = new LoginRateLimiter(3, new MutableClock());
        for (int i = 1; i <= 3; i++) limiter.check("2001:db8::" + i);           // rotating within one /64...
        assertThatThrownBy(() -> limiter.check("2001:db8::99")).isInstanceOf(TooManyAttemptsException.class); // ...is one client
    }

    /** Regression for a review finding: rotating through many addresses used to grow the map without limit. */
    @Test
    void memoryIsBoundedAndRotatingAddressesAreStillThrottledViaTheOverflowBucket() {
        var limiter = new LoginRateLimiter(5, 100, new MutableClock());
        for (int i = 0; i < 100; i++) limiter.check("10.0.0." + i);                 // fill the table with live windows
        for (int i = 0; i < 5; i++) limiter.check("198.51.100." + i);               // new keys go to the shared bucket
        assertThatThrownBy(() -> limiter.check("198.51.100.200")).isInstanceOf(TooManyAttemptsException.class);
        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(101);                 // 100 + overflow, no matter how many IPs
        for (int i = 0; i < 10_000; i++) {
            try { limiter.check("192.0.2." + (i % 250) + "-" + i); } catch (TooManyAttemptsException ignored) { }
        }
        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(101);
    }

    @Test
    void expiredWindowsAreReclaimedBeforeFallingBackToOverflow() {
        var clock = new MutableClock();
        var limiter = new LoginRateLimiter(5, 10, clock);
        for (int i = 0; i < 10; i++) limiter.check("10.0.0." + i);
        clock.now += 60_001;                                                         // all windows expire
        limiter.check("203.0.113.1");
        assertThat(limiter.trackedKeys()).isEqualTo(1);                              // reclaimed, own bucket again
    }
}
