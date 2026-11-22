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

## Medium review findings
1. Blanket IllegalArgumentException -> 400 replaced by InvalidRequestException for business rules; any other
   IllegalArgumentException is now a 500 with no internal message. Bad `assignee` returns a clear 400 instead of
   echoing "Invalid UUID string". Same reasoning as the earlier IllegalStateException fix, which I had only half-applied.
2. "Future incident date" was judged on the server's UTC clock, so a claim reported at 04:00 in KL for an incident that
   day was rejected. Market now carries its time zone and the check uses the policy market's "today" (injected Clock).
   Removed @PastOrPresent from the DTO, since it used the same server clock.

## Block 5 — Angular frontend
Accepted: standalone components + signals, plain CSS with light/dark tokens instead of Material (smaller, no theming
fight), nginx as the single origin, polling with a visible "as of" line on the dashboard.
Challenged: first polling version used timer+switchMap with no inner catchError, so one failed request would have
silently ended the claimant's auto-refresh forever. Overrode: catch per request, keep polling.
Found in the browser (not by the build): the production page rendered unstyled. Angular's critical-CSS inlining loads
the stylesheet with `onload="this.media='all'"`, an inline handler my CSP (`script-src 'self'`) blocks. Fixed by
disabling inlineCritical rather than weakening the CSP; verified no inline handlers remain.
Wiring: claims-service trusts X-Forwarded-For only from the frontend's fixed IP (172.28.0.10), so the login rate limit
sees real clients through nginx while direct :8080 calls can't spoof the header.
Tooling: `npm ci` rejected a lockfile that npm had just generated in the same image (npm 10 bug with this tree);
the image uses `npm install` against the committed lockfile. Trade-off noted in the Dockerfile.
Verified end to end in a browser: claimant submits -> officer picks up and approves 8,000 MYR -> manager dashboard
shows the exposure and workload via Kafka. No automated frontend tests yet (gap).

## Block 5b — Angular Material + Tailwind (directed by me)
Asked: rebuild the UI on Angular Material components with Tailwind for layout.
Decided before writing code: Material owns components, Tailwind owns layout/spacing, Tailwind `preflight` OFF —
its global reset is known to strip Material's button and form-field styles. System font instead of Roboto because
the CSP (`default-src 'self'`) blocks Google Fonts.
Found in the browser (twice):
1. Status pills and bordered boxes had no borders. With preflight off, Tailwind's `border` sets only border-width;
   the `border-style: solid` default lives in preflight. Rejected adding `border-solid` per element: an element with
   no width utility would then get the browser's default 3px border.
2. My first fix restored preflight's border rule globally at zero specificity (`:where(*)`). It broke Material's
   outlined text fields: a stray vertical line inside every field. Diagnosed by reading computed styles: MDC's notched
   outline sets a width on all four sides of the notch and relies on the DEFAULT `border-style: none` to hide the
   left/right sides. Zero specificity cannot help when a component relies on a default instead of a rule.
   Overrode: scope the rule to elements carrying a Tailwind border class (`:where([class*="border"])`). Verified both
   the pills (solid) and the field outline (clean) by computed style and screenshot.
Cost recorded: initial bundle 92 kB -> 164 kB transferred (334 kB -> 792 kB raw), almost all Material + theme CSS.
Budget raised to 800 kB warning on purpose; lazy routes keep screen code out of the initial load.
Verified: claimant / officer / manager flows in the browser, 0 inline handlers, 0 CSP violations, 0 font requests.

## Frontend tests (directed by me: "add tests" instead of "verified manually")
Accepted: Playwright end-to-end against the real compose stack (UI, both services, Postgres, Kafka), serial, each test
creates its own claims through the API; unit tests for the shared format helpers in the same runner.
Challenged: behind nginx every test request comes from one client IP, so the real 10/min login limit would make the
suite flaky. Rejected "log in once and reuse tokens" (hides the login UI from tests). Overrode: a `docker-compose.e2e.yml`
overlay raising ONLY the per-IP limit; the per-email lockout stays real and has its own test.
Found while running: Playwright compiles specs to CommonJS and Angular packages are ESM-only, so importing
HttpErrorResponse into a unit test failed. Overrode: `problem()` now depends on the structural shape of an HTTP error
(type-only import), which also makes the helper framework-free.
Verification of the tests themselves: 10/10 passed first time, so I broke the workbench's 409 handling on purpose; exactly
the race test failed (1 failed, 9 passed), then the file was restored unchanged.

## Rename ClaimDesk -> Lodge (directed by me)
Asked: rename the product to Lodge across the whole repo, README first.
Scope: Java packages (`com.claimdesk` -> `com.lodge`), config prefix (`claimdesk.*` -> `lodge.*`), metric names
(`claimdesk.outbox.*` -> `lodge.outbox.*`, alert rules updated), database name/user, env vars, the Angular project and
its selector prefix (`cd-` -> `lg-`), scripts and every doc. Earlier journal entries were renamed too, so names in this
log match the code.
Found while reviewing the README for the rename: it still said consumer poison messages go to `claims.events.DLT`, which
stopped being true when dead letters moved to a table. Rewrote the README as a front page (quick start, roles table,
deliverables, architecture, tests, known gaps).
Verified after the rename: claims 30/30, reporting 15/15, end-to-end 10/10. Existing local volumes need
`docker compose down -v` (the database name changed).

## Claim age and SLA in the officer queue (directed by me)
Asked: add claim-age and SLA indicators so officers can manage workload; also show how both user types use one app.
Accepted: one timestamp per claim (`status_changed_at`, set on every transition) instead of recomputing from the
timeline; per-status targets in config; SLA computed on the server with an injected Clock so UI and future alerts agree.
Challenged: a single "overdue" flag would blame the officer for a claim waiting on the claimant. Overrode: a breach in
PENDING_INFO reads "Chase claimant". Picking up a claim resets the clock (new status), which a test asserts.
Mistakes caught by the e2e run: Material renders single-select toggles as role=radio, so `getByRole('button')` never
matched; then my own sed put a comment in front of `.click()`, silently commenting the click out. Both fixed; I read the
line back instead of trusting the edit.
Known limit: the summary loads the open slices and counts in Java; at volume it becomes a SQL aggregate.
Verified: claims 33/33 (incl. backdated-claim SLA, sort, summary, claimant redaction), e2e 11/11, and a browser check.

## Fixing the three important review findings (SLA + rate limiter)
1. Decision SLA reset after each info request (an officer could stay "on track" forever by asking a question before
   the deadline). Kept `status_changed_at` honest for display, and added a separate SLA clock: review time is banked
   when the claim goes to the claimant and the clock resumes from it on the answer (V7, `Claim.transitionTo`).
   Regression test backdates 4 review days, waits 3 "claimant days", answers: due soon with ~1 day left, not fresh.
2. Banner counted claimant-side breaches as the officer's "overdue". Added `chaseClaimant` to the summary; the banner
   shows it separately in amber. Test asserts the officer's overdue count does not move.
3. Rate-limiter memory. Rejected "clear the map when full" (resets every attacker's window) and "reject new IPs"
   (locks out legitimate users). Overrode: key IPv6 by /64 (one host owns its whole /64), and once the table is full of
   live windows new keys share one overflow bucket, so memory stays flat and rotation is still throttled. Unit tests
   push 10,000 rotating addresses through a 100-key table.
Also fixed the e2e row-matching nit while in the file. Verified: claims 38/38, e2e 11/11.

## Three review follow-ups (V6 backfill, claimant redaction, SQL summary) — options chosen by me
Asked: for each of three review nits I asked for options, then picked: recompute from the timeline, role labels
instead of ids, one SQL aggregate.
Found before writing any code: deriving the V8 SQL from the runtime rule exposed a bug in my own SLA-pause fix from
the day before — on a second info request it ADDED the time since the resumed clock start, which already included the
first stretch (2 + 1 days became 5). Fixed to assignment; new test runs two request/answer cycles.
Found by the test, not by review: I asserted the whole claimant response contains no staff id or name (not just the
timeline field I had changed). It failed — the claim summary also leaked assigneeId/assigneeName. Same fix applied there.
Lesson: assert on the whole payload when testing redaction.
Challenged: moving the SLA rule into SQL means two implementations (SQL + Sla.assess). Accepted the duplication for
O(1) counts, and added a drift test that runs both over the same backdated claims and requires identical numbers. The
query takes "now" from the application clock so the banner and the per-row chips can't disagree on time.
Verified: claims 42/42, e2e 11/11.
