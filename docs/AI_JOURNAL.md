# AI Working Journal

## Scaffold (blocks 1-2)
Asked: Spring Boot skeleton + infra-only compose + Flyway schema, per a drafted scaffold.
Accepted: Infra-only compose, ddl-auto=validate, enum transition table on ClaimStatus, outbox publisher shape.
Challenged: The scaffold's BCrypt seed hash was flagged as unverified; an earlier draft of mine also used a different seed password.
Overrode: Generated the hash locally (`htpasswd -B`, $2y->$2a) for password `password`; to be verified by a real login in block 3.
Overrode: Outbox `kafka.send(msg).get()` had no timeout -> bounded to 10s so a dead broker can't hang the poller thread.

## Process slip (kept for honesty)
A zsh glob in a cleanup `rm` aborted the whole command, so the hash silently stayed stale; caught by re-checking the seed file, not by a test.

## Smoke test findings (blocks 1-2)
Verified against real Postgres + Kafka: assign 200, repeat assign 409, non-assignee approve 403, approve-after-approve/reject 409; both events published to claims.events keyed by claimId; `@JdbcTypeCode(SqlTypes.JSON)` on a String maps cleanly to jsonb (not double-encoded).
Found: bean-validation failures returned Boot's default error JSON, not RFC 7807. Fixed with `spring.mvc.problemdetails.enabled: true`; re-checked -> ProblemDetail 400.
Environment: host port 5432 was already in use, so verification ran on a temp compose copy with Postgres on 55432 (scaffold compose unchanged).
Still open: X-User-Id header is an auth bypass until JWT (block 3); outbox stalls behind one poison row (needs skip-after-N / DLQ).

## Block 3 — JWT, ownership, full command set
Asked: replace X-User-Id with JWT; add submit/requestInfo/respond/settle/reserve, timeline, queries.
Accepted: role gate in SecurityConfig (coarse) + ownership in services (data-dependent); full-snapshot outbox payload with `eventId` and claim `version` so the reporting consumer can dedupe and drop stale events.
Challenged: draft `AuthService` used a hard-coded fake BCrypt "dummy" hash for unknown emails — BCrypt rejects malformed hashes instantly, so it would leak user existence by timing. Overrode: encode a real throwaway value at startup. Test asserts identical bodies for unknown-email vs wrong-password.
Challenged: draft query mapping passed duplicated/garbled settled-amount args to `ClaimSummary`; caught on read-through before running, rewritten.
Decision: claimants get 404 (not 403) for other people's claims/policies, so ids can't be probed. Claimants never see reserve figures or RESERVE_CHANGED events.
Tooling: Docker 29 rejects Testcontainers <=1.21.3's client API version; 1.21.4 works (pinned). ITs need Docker.
Not covered by a test yet: forced serialisation failure rolling back the claim change (outbox atomicity); known gap.

## Hardening pass (before block 4)
Asked: fix the three open issues from block 3 (poison outbox row, 409-vs-500, default JWT secret).
Challenged: the obvious fix for the poison row — "dead-letter after N failures" — would have dead-lettered every healthy row during a broker outage, since each tick fails on the first row. Overrode: classify errors; retriable (timeout, broker down) never counts an attempt, only non-retriable ones do. Unit test runs 10 ticks of outage and asserts zero attempts.
Challenged: a catch-all `@ExceptionHandler(Exception.class)` on an advice that doesn't extend ResponseEntityExceptionHandler would turn framework 404/405/400 into 500. Overrode: extend it; added assertions for unknown-route 404 and validation 400.
Fixed: blanket `IllegalStateException -> 409` replaced by an explicit `BusinessConflictException`; real bugs now return a generic 500 and log the detail. This also closed the earlier test gap: a forced serialisation failure now proves the claim change, timeline row and outbox row all roll back.
Fixed: JWT secret has no default in base config (app won't start without it); `dev` profile carries a clearly-labelled local value.
Trade-off recorded: dead-lettering skips an event for that claim; consumers self-heal because events are full snapshots with a version.

## Block 4 — reporting-service
Asked: Kafka consumer + exposure/workload projections + manager-only reports + tests.
Accepted: version-guarded upsert as the real idempotency/ordering mechanism (`WHERE stored.version < incoming.version`), with `processed_event` as the exact-duplicate guard in the same transaction; JDBC instead of JPA (read model, plain SQL aggregates); own Postgres schema instead of a second database to keep local setup to one container.
Challenged: first instinct was to let a failing message retry forever via the default error handler — the consumer-side twin of the poison outbox row. Overrode: `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`, `PoisonEventException` is non-retryable, DLT partition -1 so it needn't match the source topic's partition count. Test sends garbage, then a valid event on the same key, and asserts the valid one is projected and the garbage is in the DLT.
Challenged: hand-built test JSON proves nothing about the real contract. Overrode: ran claims-service and reporting-service together against real Kafka and drove a claim through approve and settle over REST; exposure went 0 -> 8000 -> 0 and workload followed.
Found by that real run (not by any MockMvc test): role-denied requests returned **401, not 403** on a real server. The default AccessDeniedHandler uses sendError(), the container forwards to /error, which is unauthenticated, so Spring Security answers 401. Affected claims-service too (its ITs only used MockMvc, which has no error dispatch). Fix: set the status directly in an accessDeniedHandler; added a RANDOM_PORT test in both services that fails without it (verified: red before, green after).
Caught myself: JsonPath filter + `[0]` returns a nested array; test helper fixed, not the assertion weakened.
Scope additions beyond the PRD list: `/api/reports/claims-by-status` (the dashboard needs open-by-status-and-market).

## Closing the open items
Asked: fix what is fixable from the "still open" list, give advice on the rest.
Accepted: roster table (`known_officer`) fed by assignment events for the workload report; per-currency totals instead of FX; retention jobs; runbook instead of building re-drive tooling.
Challenged: I had only listed `processed_event` as unbounded. Reading my own outbox code showed published `outbox_event` rows grow forever too — same bug class on the producer side, missed in my own gap list. Fixed (dead/pending rows deliberately never auto-deleted) with a test.
Challenged: a runbook nobody has run is fiction. Ran every command against the real stack: DLT inspect, single-message re-drive (valid event placed on the DLT reappeared in the projection), and the full read-model rebuild by offset reset (state identical afterwards). The outbox revive SQL is an integration test that fails if the statement stops working.
Found while doing it: re-driving "the whole DLT" in a one-liner would loop unfixable messages straight back to the DLT; runbook re-drives by partition+offset instead. The DLT topic doesn't exist until the first failure.
Compose: one `docker compose up` for everything needs two Kafka listeners (containers can't use an advertised `localhost:9092`). Services use the `dev` profile in compose rather than a new secret in the compose file.
Gotcha repeated: zsh doesn't word-split `$VAR` holding a command; used shell functions.

## Dead-letter operability and login protection
Asked: remove "dead letters need a person with a shell" and "no login rate limiting"; describe the JWT/OIDC path.
Accepted: per-email lockout in Postgres keyed by the *email string* (not a FK) so unknown emails behave identically; per-IP limiter as an interceptor so it runs before body parsing and bcrypt; dead letters as a table instead of a DLT topic; metrics as the alert signal.
Challenged: the usual lockout returns 401 "account locked" for real users only, which leaks which emails exist. Overrode: unknown emails get the same counter and the same generic 429 body; a test asserts the bodies are byte-identical. Also refuse the CORRECT password while locked, otherwise guessing just continues.
Challenged: I first wrote the failure counter as read-modify-write; replaced with one atomic `INSERT .. ON CONFLICT DO UPDATE` (stale/expired windows restart at 1) so parallel guesses can't slip past the counter.
Challenged: replacing the Kafka DLT with a table is a design change to what block 4 shipped. Reason: a DLT topic can only be handled with console tools; a table gives list / re-drive / discard, a metric, and an audit trail. Re-drive bypasses Kafka and reuses the parser and idempotent projection, so it cannot move a claim backwards. Kept the "never drop a message" property: if recording fails the offset is not committed.
Mistake caught by the suite: put the Clock bean inside WebConfig, which depends on the rate limiter, which needs the Clock: a circular dependency that failed all 12 integration tests at startup. Moved to its own config class.
Verified on the real stack (not just MockMvc): lockout -> 429 + Retry-After 900 even for the correct password; per-IP cutoff at request 11; a real poison message through Kafka -> dead_letter -> 422 on re-drive -> discard -> gauge 1 -> 0; officer gets 403 on ops endpoints.
Not done on purpose: RS256 key split and OIDC (ADR-0002 describes both), a Prometheus registry and alert delivery (rules are documented, not run).

## Fixing the code-review findings
Proved the two surprising findings first in throwaway copies (dotless-i spelling of a locked email logged in with 200;
a 300-char Kafka key stalled its partition with 0 dead letters). Each fix has a regression test.
1. Demo seed (MANAGER / "password") moved out of default migrations to `db/demo-seed`, dev profile and tests only.
   Test boots default config and asserts zero users. Old local DBs need `down -v`.
2. Recorder wedge: key/topic TEXT, NUL sanitised, coordinates-only fallback. Challenged my first fix: catching
   DataIntegrityViolationException missed Postgres's NUL error (22P05), which Spring maps to
   DataAccessResourceFailureException, the same family as "database down". Overrode: classify by SQLState class.
3. Lockout bypass: Java lower-case key vs Postgres upper() lookup. Rejected "throttle by user id" (reintroduces account
   enumeration). Overrode: ASCII-only emails (API 400 + DB CHECK) and exact lower(email) lookup.
4. Proxy: Tomcat RemoteIpValve with trusted proxies only, not hand-parsed headers. Real-HTTP tests, off by default.
5. Compose ports bound to 127.0.0.1; LAN address verified refused.
My mistakes: a script assertion counted a phrase I'd just put in a comment so the seed rewrite silently didn't happen;
a regression test used an email with no "i". Both caught before commit.
Left (medium): blanket IllegalArgumentException -> 400; UTC "future incident date" check.
