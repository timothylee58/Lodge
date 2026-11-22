package com.claimdesk.claims;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Needs Docker (Testcontainers): real Postgres + Kafka. Seed users all have password "password". */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ClaimApiIntegrationTest {

    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("claimdesk.outbox.poll-ms", () -> "200");
        r.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo-seed"); // demo users
        r.add("claimdesk.auth.rate-limit-per-minute", () -> "100"); // the suite logs in a lot from one address
        r.add("claimdesk.jwt.secret", () -> "integration-test-secret-0123456789-abcdefghij");
    }

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.claimdesk.claims.infrastructure.OutboxCleaner cleaner;
    @SpyBean ObjectMapper json;

    static final String MOTOR_POLICY = "10000000-0000-0000-0000-000000000001"; // limit 50 000, holder Aina

    // ---------- helpers ----------
    String login(String email) throws Exception {
        var res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"password\"}".formatted(email))).andReturn();
        assertThat(res.getResponse().getStatus()).as("login " + email).isEqualTo(200);
        return JsonPath.read(res.getResponse().getContentAsString(), "$.token");
    }

    MvcResult call(MockHttpServletRequestBuilder b, String token, String body) throws Exception {
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(b).andReturn();
    }

    int status(MvcResult r) { return r.getResponse().getStatus(); }
    String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }

    String submitClaim(String claimantToken) throws Exception {
        var r = call(post("/api/claims"), claimantToken, """
                {"policyId":"%s","incidentDate":"%s","description":"Rear-ended at junction"}"""
                .formatted(MOTOR_POLICY, LocalDate.now().minusDays(1)));
        assertThat(status(r)).isEqualTo(201);
        return JsonPath.read(body(r), "$.claim.id");
    }

    String staff(String id, String action) { return "/api/staff/claims/" + id + "/" + action; }

    // ---------- tests ----------

    @Test
    void authGates() throws Exception {
        assertThat(status(call(get("/api/claims"), null, null))).isEqualTo(401);
        assertThat(status(call(get("/api/claims"), "forged.token.value", null))).isEqualTo(401);
        var bad = call(post("/api/auth/login"), null, "{\"email\":\"aina@demo.local\",\"password\":\"nope\"}");
        assertThat(status(bad)).isEqualTo(401);
        var unknown = call(post("/api/auth/login"), null, "{\"email\":\"ghost@demo.local\",\"password\":\"nope\"}");
        assertThat(body(unknown)).isEqualTo(body(bad)); // no user enumeration

        var aina = login("aina@demo.local");
        assertThat(status(call(get("/api/staff/claims"), aina, null))).as("claimant on staff API").isEqualTo(403);

        assertThat(status(call(get("/api/does-not-exist"), aina, null))).as("framework 404 must not become 500").isEqualTo(404);
        assertThat(status(call(post("/api/claims"), aina, "{}"))).as("validation").isEqualTo(400);

        var mark = login("mark@demo.local");
        var id = submitClaim(aina);
        assertThat(status(call(get("/api/staff/claims?status=SUBMITTED&assignee=unassigned"), mark, null))).isEqualTo(200);
        assertThat(status(call(post(staff(id, "assign")), mark, null))).as("manager cannot transition").isEqualTo(403);
        assertThat(status(call(post("/api/claims"), login("wei@demo.local"), "{}"))).as("officer cannot submit").isEqualTo(403);
    }

    @Test
    void fullLifecycleWithTimelineRedactionAndOrderedEvents() throws Exception {
        var aina = login("aina@demo.local");
        var wei = login("wei@demo.local");
        var id = submitClaim(aina);

        var assign = call(post(staff(id, "assign")), wei, null);
        assertThat(status(assign)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(assign), "$.claim.status")).isEqualTo("UNDER_REVIEW");

        assertThat(status(call(post(staff(id, "info-requests")), wei, "{\"question\":\"Police report number?\"}"))).isEqualTo(200);
        assertThat(status(call(post("/api/claims/" + id + "/info-responses"), aina, "{\"response\":\"PDRM/1234\"}"))).isEqualTo(200);
        assertThat(status(call(post("/api/claims/" + id + "/info-responses"), aina, "{\"response\":\"again\"}")))
                .as("no open request / wrong state").isEqualTo(409);

        assertThat(status(call(post(staff(id, "approve")), wei, "{\"reserveAmount\":60000}"))).as("reserve above limit").isEqualTo(400);
        assertThat(status(call(post(staff(id, "approve")), wei, "{\"reserveAmount\":8000}"))).isEqualTo(200);
        assertThat(status(call(patch(staff(id, "reserve")), wei, "{\"reserveAmount\":9000}"))).isEqualTo(200);
        assertThat(status(call(post(staff(id, "settle")), wei, "{\"amount\":60000}"))).as("settlement above limit").isEqualTo(400);
        var settled = call(post(staff(id, "settle")), wei, "{\"amount\":7500}");
        assertThat(status(settled)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(settled), "$.claim.status")).isEqualTo("SETTLED");
        assertThat(status(call(patch(staff(id, "reserve")), wei, "{\"reserveAmount\":1}"))).as("closed claim").isEqualTo(409);

        // Claimant view: no reserve figures anywhere, but sees the payout and the full story.
        var view = body(call(get("/api/claims/" + id), aina, null));
        assertThat((Object) JsonPath.read(view, "$.claim.reserveAmount")).isNull();
        assertThat(((Number) JsonPath.read(view, "$.claim.settledAmount")).doubleValue()).isEqualTo(7500.0);
        List<String> types = JsonPath.read(view, "$.timeline[*].type");
        assertThat(types).containsExactly("CLAIM_SUBMITTED", "CLAIM_ASSIGNED", "INFO_REQUESTED", "INFO_PROVIDED",
                "CLAIM_APPROVED", "CLAIM_SETTLED"); // RESERVE_CHANGED hidden
        assertThat(view).doesNotContain("reserveAmount\":8000").doesNotContain("9000");

        // Staff view keeps it.
        List<String> staffTypes = JsonPath.read(body(call(get("/api/claims/" + id), wei, null)), "$.timeline[*].type");
        assertThat(staffTypes).contains("RESERVE_CHANGED").hasSize(7);

        // Outbox: one row per event, all eventually published, and Kafka sees them in version order.
        var uuid = UUID.fromString(id);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id=?", Integer.class, uuid)).isEqualTo(7);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id=? and published_at is null",
                        Integer.class, uuid)).isZero());

        var versions = new ArrayList<Integer>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of("claims.events"));
            var deadline = System.currentTimeMillis() + 20_000;
            while (versions.size() < 7 && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(rec -> {
                    if (rec.key().equals(id)) versions.add(((Number) JsonPath.read(rec.value(), "$.version")).intValue());
                });
            }
        }
        assertThat(versions).hasSize(7).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void concurrentAssignExactlyOneWins() throws Exception {
        var id = submitClaim(login("aina@demo.local"));
        var wei = login("wei@demo.local");
        var priya = login("priya@demo.local");

        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (var token : List.of(wei, priya)) {
            results.add(pool.submit(() -> { start.await(); return status(call(post(staff(id, "assign")), token, null)); }));
        }
        start.countDown();
        var codes = new ArrayList<Integer>();
        for (var f : results) codes.add(f.get(20, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("select count(*) from claim_event where claim_id=? and type='CLAIM_ASSIGNED'",
                Integer.class, UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void ownershipIsEnforcedOnTheBackend() throws Exception {
        var aina = login("aina@demo.local");
        var id = submitClaim(aina);
        var wei = login("wei@demo.local");
        var priya = login("priya@demo.local");
        call(post(staff(id, "assign")), wei, null);

        assertThat(status(call(post(staff(id, "approve")), priya, "{\"reserveAmount\":100}"))).as("not the assignee").isEqualTo(403);
        assertThat(status(call(post(staff(id, "reject")), priya, "{\"reason\":\"x\"}"))).isEqualTo(403);

        // A second claimant must not see (or answer on) Aina's claim — and gets 404, not 403.
        jdbc.update("insert into app_user (id,name,email,password,role,market) select gen_random_uuid(),'Eve','eve@demo.local',password,'CLAIMANT','MY' from app_user where email='aina@demo.local'");
        var eve = login("eve@demo.local");
        assertThat(status(call(get("/api/claims/" + id), eve, null))).isEqualTo(404);
        assertThat(status(call(post("/api/claims/" + id + "/info-responses"), eve, "{\"response\":\"hi\"}"))).isEqualTo(404);
        assertThat(body(call(get("/api/claims"), eve, null))).isEqualTo("[]");
        var submitOnAinasPolicy = call(post("/api/claims"), eve,
                "{\"policyId\":\"%s\",\"incidentDate\":\"%s\",\"description\":\"x\"}".formatted(MOTOR_POLICY, LocalDate.now()));
        assertThat(status(submitOnAinasPolicy)).as("someone else's policy").isEqualTo(404);
    }

    @Test
    void serialisationFailureRollsBackTheClaimChangeAndReturns500() throws Exception {
        var id = submitClaim(login("aina@demo.local"));
        var wei = login("wei@demo.local");
        var uuid = UUID.fromString(id);

        doThrow(new JsonProcessingException("boom-internal-detail") {}).when(json).writeValueAsString(any());
        MvcResult failed;
        try {
            failed = call(post(staff(id, "assign")), wei, null);
        } finally {
            reset(json);
        }

        assertThat(status(failed)).isEqualTo(500);
        assertThat(body(failed)).doesNotContain("boom-internal-detail");
        // Atomicity: no half-applied change, no timeline row, no outbox row for the failed command.
        assertThat(jdbc.queryForObject("select status from claim where id=?", String.class, uuid)).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("select assignee_id from claim where id=?", UUID.class, uuid)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from claim_event where claim_id=?", Integer.class, uuid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id=?", Integer.class, uuid)).isEqualTo(1);
        // ...and the claim is still perfectly usable.
        assertThat(status(call(post(staff(id, "assign")), wei, null))).isEqualTo(200);
    }

    /** MockMvc skips the servlet container's error dispatch, so it can't catch a 403 that turns into 401 via /error. */
    @Test
    void roleDenialIsA403OnARealServerNotA401() throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/staff/claims"))
                .header("Authorization", "Bearer " + login("aina@demo.local")).build();
        assertThat(HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.discarding()).statusCode()).as("claimant on staff API").isEqualTo(403);
        var anon = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/staff/claims")).build();
        assertThat(HttpClient.newHttpClient().send(anon, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
    }

    @Test
    void cleanupDeletesOnlyOldPublishedRows() {
        var oldPublished = UUID.randomUUID(); var oldDead = UUID.randomUUID(); var pending = UUID.randomUUID();
        String ins = "insert into outbox_event (id, aggregate_id, event_type, topic, payload, created_at, published_at, dead_at) "
                + "values (?, gen_random_uuid(), 'T', 'no.such.topic.cleanup', '{}'::jsonb, now() - interval '30 days', %s, %s)";
        jdbc.update(ins.formatted("now() - interval '20 days'", "null"), oldPublished);
        jdbc.update(ins.formatted("null", "now() - interval '20 days'"), oldDead);
        jdbc.update(ins.formatted("null", "now()").replace("now())", "null)"), pending); // pending: neither published nor dead
        cleaner.prune();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where id=?", Integer.class, oldPublished)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where id=?", Integer.class, oldDead)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where id=?", Integer.class, pending)).isEqualTo(1);
        jdbc.update("delete from outbox_event where id in (?, ?)", oldDead, pending); // don't leave rows for the publisher
    }

    /** Proves the runbook's re-drive SQL actually works: a dead row stays dead until revived, then gets published. */
    @Test
    void deadRowIsRedrivenByTheRunbookSql() throws Exception {
        var id = UUID.randomUUID();
        jdbc.update("insert into outbox_event (id, aggregate_id, event_type, topic, payload, attempts, last_error, dead_at) "
                + "values (?, gen_random_uuid(), 'REDRIVE_TEST', 'claims.events', '{\"redrive\":true}'::jsonb, 3, 'boom', now())", id);
        Thread.sleep(1500); // several publisher ticks (poll-ms=200)
        assertThat(jdbc.queryForObject("select published_at is null from outbox_event where id=?", Boolean.class, id)).isTrue();

        // --- exactly the statement in docs/RUNBOOK.md ---
        jdbc.update("UPDATE outbox_event SET dead_at = NULL, attempts = 0, last_error = NULL WHERE id = ?", id);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select published_at is not null from outbox_event where id=?", Boolean.class, id)).isTrue());
    }

    // ---------- login throttling ----------

    MvcResult tryLogin(String email, String password, String remoteAddr) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(r -> { r.setRemoteAddr(remoteAddr); return r; })
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn();
    }

    void addUser(String email) {
        jdbc.update("insert into app_user (id,name,email,password,role,market) select gen_random_uuid(),'T',?,password,'CLAIMANT','MY' from app_user where email='aina@demo.local'", email);
    }

    @Test
    void accountLocksAfterRepeatedFailuresEvenForTheCorrectPasswordAndUnlocksAfterwards() throws Exception {
        addUser("lockme@demo.local");
        for (int i = 0; i < 5; i++) assertThat(status(tryLogin("lockme@demo.local", "wrong", "10.1.1.1"))).isEqualTo(401);

        var locked = tryLogin("lockme@demo.local", "password", "10.1.1.1");
        assertThat(status(locked)).as("correct password refused while locked").isEqualTo(429);
        assertThat(Long.parseLong(locked.getResponse().getHeader("Retry-After"))).isBetween(1L, 900L);

        // Lock expires -> works again, and success clears the counter.
        jdbc.update("update login_throttle set locked_until = now() - interval '1 second' where email='lockme@demo.local'");
        assertThat(status(tryLogin("lockme@demo.local", "password", "10.1.1.1"))).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from login_throttle where email='lockme@demo.local'", Integer.class)).isZero();
    }

    @Test
    void unknownEmailsAreThrottledIdenticallySoLockoutDoesNotRevealAccounts() throws Exception {
        for (int i = 0; i < 5; i++) assertThat(status(tryLogin("nobody-here@demo.local", "x", "10.2.2.2"))).isEqualTo(401);
        var unknown = tryLogin("nobody-here@demo.local", "x", "10.2.2.2");

        addUser("real-one@demo.local");
        for (int i = 0; i < 5; i++) tryLogin("real-one@demo.local", "x", "10.2.2.3");
        var known = tryLogin("real-one@demo.local", "x", "10.2.2.3");

        assertThat(status(unknown)).isEqualTo(429);
        assertThat(status(known)).isEqualTo(429);
        assertThat(body(unknown)).isEqualTo(body(known));
    }

    @Test
    void successfulLoginResetsTheFailureCounter() throws Exception {
        addUser("resets@demo.local");
        for (int i = 0; i < 4; i++) tryLogin("resets@demo.local", "wrong", "10.3.3.3");
        assertThat(status(tryLogin("resets@demo.local", "password", "10.3.3.3"))).isEqualTo(200);
        for (int i = 0; i < 4; i++) assertThat(status(tryLogin("resets@demo.local", "wrong", "10.3.3.3"))).isEqualTo(401);
        assertThat(status(tryLogin("resets@demo.local", "password", "10.3.3.3"))).as("4+4 failures never reached 5 in a row").isEqualTo(200);
    }

    @Test
    void perIpRateLimitCutsOffBeforeAnyPasswordWork() throws Exception {
        int ok = 0;
        MvcResult last = null;
        for (int i = 0; i < 101; i++) {
            last = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}")
                    .with(r -> { r.setRemoteAddr("10.9.9.9"); return r; })).andReturn(); // invalid body: cheap, still counted
            if (status(last) == 400) ok++;
        }
        assertThat(ok).isEqualTo(100);
        assertThat(status(last)).isEqualTo(429);
        assertThat(last.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(status(tryLogin("aina@demo.local", "password", "10.9.9.10"))).as("other IPs unaffected").isEqualTo(200);
    }

    // ---------- dead-letter ops API + alert metrics ----------

    UUID insertDeadRow() {
        var id = UUID.randomUUID();
        jdbc.update("insert into outbox_event (id, aggregate_id, event_type, topic, payload, attempts, last_error, dead_at) "
                + "values (?, gen_random_uuid(), 'OPS_TEST', 'claims.events', '{\"ops\":true}'::jsonb, 3, 'RecordTooLarge', now())", id);
        return id;
    }

    double gauge(String token, String name) throws Exception {
        var r = call(get("/actuator/metrics/" + name), token, null);
        assertThat(status(r)).as(name).isEqualTo(200);
        return ((Number) JsonPath.read(body(r), "$.measurements[0].value")).doubleValue();
    }

    @Test
    void deadRowsAreVisibleAsAMetricListedAndRedrivenByAManagerOnly() throws Exception {
        var mark = login("mark@demo.local");
        var wei = login("wei@demo.local");
        double before = gauge(mark, "claimdesk.outbox.dead");
        var id = insertDeadRow();

        assertThat(gauge(mark, "claimdesk.outbox.dead")).as("alert signal").isEqualTo(before + 1);
        List<String> ids = JsonPath.read(body(call(get("/api/ops/outbox/dead"), mark, null)), "$[*].id");
        assertThat(ids).contains(id.toString());

        assertThat(status(call(get("/api/ops/outbox/dead"), wei, null))).as("officer").isEqualTo(403);
        assertThat(status(call(post("/api/ops/outbox/" + id + "/redrive"), wei, null))).isEqualTo(403);
        assertThat(status(call(get("/actuator/metrics/claimdesk.outbox.dead"), wei, null))).as("metrics are not public").isEqualTo(403);
        assertThat(status(call(get("/actuator/health"), null, null))).as("health stays public").isEqualTo(200);

        assertThat(status(call(post("/api/ops/outbox/" + id + "/redrive"), mark, null))).isEqualTo(200);
        assertThat(status(call(post("/api/ops/outbox/" + id + "/redrive"), mark, null))).as("not dead any more").isEqualTo(409);
        assertThat(status(call(post("/api/ops/outbox/" + UUID.randomUUID() + "/redrive"), mark, null))).isEqualTo(404);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select published_at is not null from outbox_event where id=?", Boolean.class, id)).isTrue());
        assertThat(gauge(mark, "claimdesk.outbox.dead")).isEqualTo(before);
    }

    @Test
    void redriveAllRequeuesEveryDeadRow() throws Exception {
        var mark = login("mark@demo.local");
        var a = insertDeadRow(); var b = insertDeadRow();
        var r = call(post("/api/ops/outbox/redrive-all"), mark, null);
        assertThat(status(r)).isEqualTo(200);
        assertThat(((Number) JsonPath.read(body(r), "$.redriven")).intValue()).isGreaterThanOrEqualTo(2);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from outbox_event where id in (?, ?) and published_at is not null",
                        Integer.class, a, b)).isEqualTo(2));
    }

    /** Regression for a review finding: a dotless-i spelling logged in while the real address was locked. */
    @Test
    void alternativeUnicodeSpellingsCannotBypassTheLockout() throws Exception {
        var email = "victim@demo.local"; // must contain an 'i' for the dotless variant to differ
        addUser(email);
        for (int i = 0; i < 5; i++) tryLogin(email, "wrong", "10.4.4.4");
        assertThat(status(tryLogin(email, "password", "10.4.4.4"))).isEqualTo(429);

        var variant = email.replace("i", "\u0131");
        assertThat(variant).isNotEqualTo(email);
        assertThat(status(tryLogin(variant, "password", "10.4.4.4")))
                .as("dotless-i variant is rejected, not authenticated").isEqualTo(400);
        assertThat(status(tryLogin("VICTIM@DEMO.LOCAL", "password", "10.4.4.4")))
                .as("ASCII case variants share the same lock").isEqualTo(429);
        assertThat(status(tryLogin("aina@demo.local", "x".repeat(129), "10.4.4.5"))).as("oversized password").isEqualTo(400);
        assertThat(status(tryLogin("AINA@Demo.Local", "password", "10.4.4.5"))).as("case-insensitive login still works").isEqualTo(200);
    }
}
