# Panel questions — prepared answers with evidence

Each answer points at code or a test you can open live. Demo: `docker compose up --build` -> http://localhost:4200.

## 1. Why two services and not one, or five?
**Two because there is exactly one real boundary: the write side (system of record) vs the read side (analytics that may lag).**
- `claims-service` owns every write and the state machine; it must be strongly consistent and answer the user now (REST).
- `reporting-service` owns exposure/workload projections; it can lag, be rebuilt by replaying Kafka, and scale on its own.
- **Not one:** reporting queries would compete with transactional writes, and there'd be no honest place to use Kafka.
- **Not five** (service per entity): more network hops, distributed transactions and plumbing under a 5-hour cap, with
  no independent scaling or ownership benefit. Claims, info requests and timeline change together in one transaction.
- Evidence: `docs/adr/0001-modular-monolith-plus-reporting.md`, REST-vs-Kafka rule in `docs/ARCHITECTURE.md`.

## 2. What happens if Kafka is down when a claim is approved?
**The approval succeeds; the event waits in the outbox and is published when Kafka is back.**
- The claim change, its timeline row and its outbox row commit in **one DB transaction**
  (`ClaimCommandService#record`) — never a committed claim without an event, never an event for a rolled-back claim.
- The poller sends outside any DB transaction; broker errors are *retriable*: it stops the tick and retries, **without
  counting attempts**, so an outage never dead-letters healthy rows (`OutboxPublisher`,
  `OutboxPublisherTest#brokerOutageNeverBurnsAttemptsOrDeadLettersHealthyRows`).
- Truly bad rows (non-retriable) are dead-lettered after 3 attempts so they can't block the queue; ops API re-drives them.
- Alert: `lodge.outbox.oldest.pending.seconds` grows during an outage (`docs/RUNBOOK.md` §5).
- At-least-once delivery -> the consumer is idempotent (`processed_event` + version-guarded upsert).
- Live demo: `docker compose stop kafka`, approve in the UI (works), dashboard stays stale; `docker compose start kafka`,
  dashboard catches up.

## 3. Two officers click assign at the same moment?
**Exactly one wins; the other gets 409 and the UI explains it.**
- `@Version` optimistic locking on `Claim` + the state machine (`SUBMITTED -> UNDER_REVIEW` only once).
  Whichever check trips, the loser gets RFC 7807 409 (`GlobalExceptionHandler`).
- Backend proof: `ClaimApiIntegrationTest#concurrentAssignExactlyOneWins` (two threads, one 200, one 409, one timeline row).
- UI proof: `frontend/e2e/concurrency.spec.ts` — the loser sees "The claim has been refreshed … Assigned to Priya".

## 4. Who owns the API contract?
**The backend.** Controllers + DTOs are the source of truth, published as OpenAPI by springdoc
(`http://localhost:8080/v3/api-docs`, Swagger UI at `/swagger-ui.html`; reporting at `:8081`). Errors are RFC 7807.
**Honest gap:** the Angular types in `frontend/src/app/core/models.ts` are *hand-written* to mirror those DTOs, not yet
generated. Next step: generate them from `/v3/api-docs` (e.g. `openapi-typescript`) in CI so a backend change that breaks
the client fails the build. The e2e suite currently catches contract drift at runtime.

## 5. How is exposure calculated, and how stale can it be?
- **Reserve** = amount set aside per claim (set on approval, adjustable while open).
  **Exposure = Σ reserve of claims not SETTLED or REJECTED**, grouped by market, type and **currency** — never summed
  across currencies (no FX; documented gap). `ReportQueries#exposure`, `#exposureByCurrency`.
- Computed in reporting-service from a projection fed by `claims.events`; each event is a full claim snapshot with a
  version, so late or duplicate events can't move a claim backwards.
- **Staleness:** normally ~1–2 s (outbox poll 1 s + consumer). Unbounded only if Kafka or reporting is down — then the
  write side keeps working and reports catch up. Every report returns `asOf` (newest event reflected) and the dashboard
  shows "Data as of …". Evidence: `ReportingIntegrationTest`, `frontend/e2e/lifecycle.spec.ts`.

## 6. Show a line of AI-generated code you rejected, and why.
**Login "dummy hash" (user enumeration by timing).** The AI wrote:
```java
private static final String DUMMY = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5BWOxrOxVL4VQ8x1Fq3a8E2Qw4pBy";
```
It is not a valid BCrypt hash, so for unknown emails `matches()` returns instantly while real emails take ~100 ms —
an attacker can tell which accounts exist. Replaced with a real hash encoded at startup
(`this.dummyHash = encoder.encode("not-a-real-password")` in `AuthService`); the test asserts identical 401 bodies.
More, each with a commit: `docs/DIRECTION_LOG.md` §2 (Unicode lockout bypass, seed backdoor, 403->401 on a real server,
partition-wedging dead-letter recorder, UTC date bug, CSP vs Angular CSS inlining).
