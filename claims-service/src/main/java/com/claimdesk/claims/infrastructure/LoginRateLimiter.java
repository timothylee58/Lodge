package com.claimdesk.claims.infrastructure;

import com.claimdesk.claims.application.TooManyAttemptsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client-IP fixed-window limit on POST /api/auth/login, applied BEFORE the body is parsed or any bcrypt work
 * is done (cost-based abuse is bounded too). In-memory = per instance; behind several instances or a CDN, enforce
 * this at the gateway instead. remoteAddr only: X-Forwarded-For is client-controlled and is NOT trusted here.
 */
@Component
public class LoginRateLimiter implements HandlerInterceptor {
    private static final long WINDOW_MS = 60_000;
    private static final int MAX_TRACKED = 10_000;
    private record Window(long startMs, int count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxPerMinute;
    private final Clock clock;

    public LoginRateLimiter(@Value("${claimdesk.auth.rate-limit-per-minute:10}") int maxPerMinute, Clock clock) {
        this.maxPerMinute = maxPerMinute;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        check(req.getRemoteAddr());
        return true;
    }

    public void check(String clientKey) {
        long now = clock.millis();
        if (windows.size() > MAX_TRACKED) windows.entrySet().removeIf(e -> now - e.getValue().startMs() >= WINDOW_MS);
        var w = windows.compute(clientKey, (k, old) ->
                old == null || now - old.startMs() >= WINDOW_MS ? new Window(now, 1) : new Window(old.startMs(), old.count() + 1));
        if (w.count() > maxPerMinute) {
            throw new TooManyAttemptsException((WINDOW_MS - (now - w.startMs()) + 999) / 1000);
        }
    }
}
