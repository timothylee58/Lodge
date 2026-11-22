package com.lodge.claims.infrastructure;

import com.lodge.claims.application.TooManyAttemptsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Clock;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client fixed-window limit on POST /api/auth/login, applied BEFORE the body is parsed or any bcrypt work
 * is done. In-memory = per instance; behind several instances or a CDN, enforce this at the gateway instead.
 *
 * Memory is bounded two ways (a review finding: rotating addresses used to grow the map without limit):
 *  - IPv6 clients are keyed by their /64 network, since one host can trivially use any address inside its /64;
 *  - at most {@code maxTracked} keys are held. Once full, NEW keys share one overflow bucket with the same limit,
 *    so an address-rotation attack is still throttled and memory stays flat (legitimate new clients degrade to the
 *    shared bucket only while the table is full of live windows).
 */
@Component
public class LoginRateLimiter implements HandlerInterceptor {
    static final String OVERFLOW = "overflow";
    private static final long WINDOW_MS = 60_000;
    private record Window(long startMs, int count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxPerMinute;
    private final int maxTracked;
    private final Clock clock;

    @Autowired
    public LoginRateLimiter(@Value("${lodge.auth.rate-limit-per-minute:10}") int maxPerMinute,
                            @Value("${lodge.auth.rate-limit-max-tracked:10000}") int maxTracked, Clock clock) {
        this.maxPerMinute = maxPerMinute;
        this.maxTracked = maxTracked;
        this.clock = clock;
    }

    public LoginRateLimiter(int maxPerMinute, Clock clock) { this(maxPerMinute, 10_000, clock); }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        check(req.getRemoteAddr());
        return true;
    }

    public void check(String remoteAddr) {
        long now = clock.millis();
        var key = keyFor(remoteAddr);
        if (!windows.containsKey(key) && windows.size() >= maxTracked) {
            windows.entrySet().removeIf(e -> !OVERFLOW.equals(e.getKey()) && now - e.getValue().startMs() >= WINDOW_MS);
            if (windows.size() >= maxTracked) key = OVERFLOW; // still full of live windows: share one bucket
        }
        var w = windows.compute(key, (k, old) ->
                old == null || now - old.startMs() >= WINDOW_MS ? new Window(now, 1) : new Window(old.startMs(), old.count() + 1));
        if (w.count() > maxPerMinute) {
            throw new TooManyAttemptsException((WINDOW_MS - (now - w.startMs()) + 999) / 1000);
        }
    }

    int trackedKeys() { return windows.size(); }

    /** IPv4 as-is; IPv6 reduced to its /64 network. remoteAddr is always an IP literal, so no DNS lookup happens. */
    static String keyFor(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.indexOf(':') < 0) return String.valueOf(remoteAddr);
        try {
            var addr = InetAddress.getByName(remoteAddr);
            if (addr instanceof Inet6Address) {
                var b = addr.getAddress();
                return HexFormat.of().formatHex(b, 0, 8) + "::/64";
            }
            return addr.getHostAddress();
        } catch (Exception e) {
            return remoteAddr;
        }
    }
}
