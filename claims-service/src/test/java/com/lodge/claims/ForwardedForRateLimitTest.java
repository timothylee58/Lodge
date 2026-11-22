package com.lodge.claims;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real HTTP (Tomcat's RemoteIpValve only exists on a real server). Regression for a review finding: behind a proxy
 * every user shared the proxy's IP and therefore one rate-limit bucket.
 */
@Testcontainers
class ForwardedForRateLimitTest {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    static void common(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("lodge.jwt.secret", () -> "forwarded-for-test-secret-0123456789abcdef");
        r.add("lodge.auth.rate-limit-per-minute", () -> "3");
        r.add("server.forward-headers-strategy", () -> "native");
    }

    static int login(int port, String xff) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (xff != null) b.header("X-Forwarded-For", xff);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class BehindTrustedProxy {
        @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
            common(r);
            r.add("server.tomcat.remoteip.internal-proxies", () -> "127\\.0\\.0\\.1"); // the "proxy" is loopback
        }
        @LocalServerPort int port;

        @Test
        void eachRealClientGetsItsOwnBucketAndCannotSpoofAFreshOne() throws Exception {
            for (int i = 0; i < 3; i++) assertThat(login(port, "203.0.113.10")).isEqualTo(400);
            assertThat(login(port, "203.0.113.10")).as("client A over the limit").isEqualTo(429);
            assertThat(login(port, "203.0.113.20")).as("client B unaffected").isEqualTo(400);
            // A prepends a fake address; the proxy appended the real one, which is what counts.
            assertThat(login(port, "198.51.100.99, 203.0.113.10")).as("spoofed prefix ignored").isEqualTo(429);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class FromAnUntrustedAddress {
        @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
            common(r);
            r.add("server.tomcat.remoteip.internal-proxies", () -> "10\\.99\\.99\\.99"); // loopback is NOT a proxy
        }
        @LocalServerPort int port;

        @Test
        void xForwardedForIsIgnoredSoItCannotBeUsedToDodgeTheLimit() throws Exception {
            for (int i = 0; i < 3; i++) assertThat(login(port, "203.0.113." + i)).isEqualTo(400);
            assertThat(login(port, "203.0.113.200")).as("same real client, new fake header").isEqualTo(429);
        }
    }
}
