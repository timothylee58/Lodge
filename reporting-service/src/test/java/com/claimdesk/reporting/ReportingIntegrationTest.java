package com.claimdesk.reporting;

import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Needs Docker. Tests share one DB, so each uses its own market code / officer to stay independent. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ReportingIntegrationTest {
    static final String SECRET = "integration-test-secret-0123456789-abcdefghij";
    static final String TOPIC = "claims.events";

    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> pg.getJdbcUrl() + "&currentSchema=reporting");
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("reporting.jwt.secret", () -> SECRET);
    }

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.claimdesk.reporting.projection.ProcessedEventPruner pruner;
    @Autowired KafkaTemplate<Object, Object> producer;

    // ---------- helpers ----------
    static String event(UUID eventId, UUID claimId, long version, String status, String market, String currency,
                        String reserve, UUID assignee, String assigneeName) {
        return """
                {"eventId":"%s","eventType":"X","occurredAt":"%s","claimId":"%s","claimNumber":"CLM-%d","version":%d,
                 "status":"%s","market":"%s","type":"MOTOR","currency":"%s","reserveAmount":%s,
                 "assigneeId":%s,"assigneeName":%s,"someFutureField":"ignored"}"""
                .formatted(eventId, Instant.now(), claimId, Math.abs(claimId.hashCode()) % 100000, version, status,
                        market, currency, reserve,
                        assignee == null ? "null" : "\"" + assignee + "\"",
                        assigneeName == null ? "null" : "\"" + assigneeName + "\"");
    }

    void send(UUID claimId, String payload) { producer.send(TOPIC, claimId.toString(), payload); }

    String token(String role) {
        return Jwts.builder().subject(UUID.randomUUID().toString()).claim("role", role)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    String report(String path) throws Exception {
        var res = mvc.perform(get(path).header("Authorization", "Bearer " + token("MANAGER"))).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return res.getResponse().getContentAsString();
    }

    BigDecimal exposure(String market) throws Exception {
        List<Object> v = JsonPath.read(report("/api/reports/exposure"), "$.rows[?(@.market=='" + market + "')].exposure");
        return v.isEmpty() ? BigDecimal.ZERO : new BigDecimal(v.get(0).toString());
    }

    /** First match of a JsonPath filter expression (filters always return a list). */
    static int num(String json, String path) {
        List<Object> v = JsonPath.read(json, path);
        assertThat(v).as(path).isNotEmpty();
        return ((Number) v.get(0)).intValue();
    }

    boolean processed(UUID eventId) {
        return jdbc.queryForObject("select count(*) from processed_event where event_id=?", Integer.class, eventId) > 0;
    }

    // ---------- tests ----------

    @Test
    void projectsExposureAndWorkloadAndIgnoresUnknownFields() throws Exception {
        var wei = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        send(a, event(UUID.randomUUID(), a, 0, "SUBMITTED", "TH", "THB", "0", null, null));
        send(a, event(UUID.randomUUID(), a, 1, "UNDER_REVIEW", "TH", "THB", "0", wei, "Wei Officer"));
        send(a, event(UUID.randomUUID(), a, 2, "APPROVED", "TH", "THB", "8000.00", wei, "Wei Officer"));
        send(b, event(UUID.randomUUID(), b, 0, "SUBMITTED", "TH", "THB", "0", null, null));
        send(b, event(UUID.randomUUID(), b, 1, "UNDER_REVIEW", "TH", "THB", "0", wei, "Wei Officer"));
        send(b, event(UUID.randomUUID(), b, 2, "PENDING_INFO", "TH", "THB", "0", wei, "Wei Officer"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(exposure("TH")).isEqualByComparingTo("8000"));

        var exposure = report("/api/reports/exposure");
        assertThat(num(exposure, "$.rows[?(@.market=='TH')].openClaims")).isEqualTo(2);
        assertThat((String) JsonPath.read(exposure, "$.asOf")).isNotBlank();

        var workload = report("/api/reports/workload");
        assertThat(num(workload, "$.officers[?(@.assigneeId=='" + wei + "')].openClaims")).isEqualTo(2);
        assertThat(num(workload, "$.officers[?(@.assigneeId=='" + wei + "')].approved")).isEqualTo(1);
        assertThat(num(workload, "$.officers[?(@.assigneeId=='" + wei + "')].pendingInfo")).isEqualTo(1);

        var byStatus = report("/api/reports/claims-by-status");
        assertThat(JsonPath.<List<Object>>read(byStatus, "$.rows[?(@.market=='TH' && @.status=='APPROVED')]")).hasSize(1);
    }

    @Test
    void duplicateAndOutOfOrderEventsNeverMoveAClaimBackwards() throws Exception {
        var claim = UUID.randomUUID();
        var e1 = UUID.randomUUID(); var e3 = UUID.randomUUID(); var late2 = UUID.randomUUID(); var sentinel = UUID.randomUUID();
        var officer = UUID.randomUUID();

        send(claim, event(e1, claim, 1, "APPROVED", "ID", "IDR", "1000", officer, "Olivia"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(exposure("ID")).isEqualByComparingTo("1000"));

        send(claim, event(e1, claim, 1, "APPROVED", "ID", "IDR", "1000", officer, "Olivia")); // exact redelivery
        send(claim, event(e3, claim, 3, "SETTLED", "ID", "IDR", "0", officer, "Olivia"));      // newer
        send(claim, event(late2, claim, 2, "APPROVED", "ID", "IDR", "9999", officer, "Olivia")); // arrives late
        send(claim, event(sentinel, claim, 3, "SETTLED", "ID", "IDR", "0", officer, "Olivia")); // same key => processed after the above

        await().atMost(Duration.ofSeconds(30)).until(() -> processed(sentinel));
        assertThat(processed(late2)).isTrue();
        assertThat(exposure("ID")).as("settled stays settled, late v2 ignored").isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select version from claim_projection where claim_id=?", Long.class, claim)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from processed_event where event_id=?", Integer.class, e1))
                .as("duplicate recorded once").isEqualTo(1);
    }

    @Test
    void poisonMessageGoesToDltAndDoesNotBlockThePartition() {
        var claim = UUID.randomUUID();
        var good = UUID.randomUUID();

        send(claim, "this is not json");
        send(claim, """
                {"eventId":"%s","claimId":"%s"}""".formatted(UUID.randomUUID(), claim)); // parses, but required fields missing
        send(claim, event(good, claim, 1, "APPROVED", "HK", "HKD", "500", null, null));

        await().atMost(Duration.ofSeconds(30)).until(() -> processed(good));
        assertThat(jdbc.queryForObject("select reserve_amount from claim_projection where claim_id=?", BigDecimal.class, claim))
                .isEqualByComparingTo("500");

        var dead = new ArrayList<String>();
        try (var c = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            c.subscribe(List.of(TOPIC + ".DLT"));
            var deadline = System.currentTimeMillis() + 20_000;
            while (dead.size() < 2 && System.currentTimeMillis() < deadline) {
                c.poll(Duration.ofMillis(500)).forEach(r -> { if (claim.toString().equals(r.key())) dead.add(r.value()); });
            }
        }
        assertThat(dead).hasSize(2).anyMatch(v -> v.equals("this is not json"));
    }

    @Test
    void reportsAreManagerOnly() throws Exception {
        assertThat(mvc.perform(get("/api/reports/exposure")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/reports/exposure").header("Authorization", "Bearer forged.token.here"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        for (var role : List.of("CLAIMANT", "OFFICER")) {
            assertThat(mvc.perform(get("/api/reports/workload").header("Authorization", "Bearer " + token(role)))
                    .andReturn().getResponse().getStatus()).as(role).isEqualTo(403);
        }
        assertThat(mvc.perform(get("/api/reports/workload").header("Authorization", "Bearer " + token("MANAGER")))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    /** MockMvc skips the servlet container's error dispatch, so it can't catch a 403 that turns into 401 via /error. */
    @Test
    void roleDenialIsA403OnARealServerNotA401() throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/reports/exposure"))
                .header("Authorization", "Bearer " + token("OFFICER")).build();
        assertThat(HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.discarding()).statusCode()).as("officer on reports").isEqualTo(403);
        var anon = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/reports/exposure")).build();
        assertThat(HttpClient.newHttpClient().send(anon, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
    }

    @Test
    void officerWhoClearedTheirQueueStillAppearsWithZero() throws Exception {
        var officer = UUID.randomUUID(); var claim = UUID.randomUUID(); var last = UUID.randomUUID();
        send(claim, event(UUID.randomUUID(), claim, 1, "UNDER_REVIEW", "AU", "AUD", "0", officer, "Zed Officer"));
        send(claim, event(last, claim, 2, "SETTLED", "AU", "AUD", "0", officer, "Zed Officer"));
        await().atMost(Duration.ofSeconds(30)).until(() -> processed(last));

        var workload = report("/api/reports/workload");
        assertThat(num(workload, "$.officers[?(@.assigneeId=='" + officer + "')].openClaims")).isZero();
        List<Object> name = JsonPath.read(workload, "$.officers[?(@.assigneeId=='" + officer + "')].assigneeName");
        assertThat(name).containsExactly("Zed Officer");
    }

    @Test
    void exposureTotalsPerCurrencyNeverMixCurrencies() throws Exception {
        var a = UUID.randomUUID(); var b = UUID.randomUUID(); var last = UUID.randomUUID();
        send(a, event(UUID.randomUUID(), a, 1, "APPROVED", "SG", "SGD", "100", null, null));
        send(b, event(last, b, 1, "APPROVED", "MY", "MYR", "7", null, null));
        await().atMost(Duration.ofSeconds(30)).until(() -> processed(last));
        var body = report("/api/reports/exposure");
        List<Object> sgd = JsonPath.read(body, "$.totalsByCurrency[?(@.currency=='SGD')].exposure");
        assertThat(sgd).isNotEmpty();
        assertThat(new BigDecimal(sgd.get(0).toString())).isGreaterThanOrEqualTo(new BigDecimal("100"));
        assertThat(JsonPath.<List<Object>>read(body, "$.totalsByCurrency[?(@.currency=='MYR')]")).isNotEmpty();
    }

    @Test
    void pruningRemovesOldProcessedEventsOnly() {
        var old = UUID.randomUUID(); var fresh = UUID.randomUUID();
        jdbc.update("insert into processed_event (event_id, processed_at) values (?, now() - interval '10 days')", old);
        jdbc.update("insert into processed_event (event_id) values (?)", fresh);
        assertThat(pruner.prune()).isGreaterThanOrEqualTo(1);
        assertThat(processed(old)).isFalse();
        assertThat(processed(fresh)).isTrue();
    }
}
