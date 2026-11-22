# Lodge

**Lodge** is a claims intake and handling MVP. Claimants *lodge* an incident and follow it to settlement, claims
officers work a shared queue, and managers see live liability exposure and workload.

It is built backend-first as a take-home exercise: two Spring Boot services (a write-side system of record and an
event-driven reporting service), Kafka with a transactional outbox, and an Angular Material + Tailwind frontend.

> **No public hosting.** This repository is source only. The app runs on your machine with Docker Compose, and every
> port is bound to `127.0.0.1`.

## Quick start

```bash
docker compose up --build
# if port 5432 is already taken on your machine:
POSTGRES_PORT=55432 docker compose up --build
```

Open **http://localhost:4200** and sign in with a demo account (password `password` for all):

| Role | Email | What to try |
|---|---|---|
| Claimant | `aina@demo.local` | Report an incident, answer an information request, see the payout |
| Claims officer (MY) | `wei@demo.local` | Pick up a claim, request info, approve with a reserve, settle |
| Claims officer (SG) | `priya@demo.local` | Race Wei for the same claim (one gets a friendly 409) |
| Manager | `mark@demo.local` | Exposure by currency and market, officer workload, "data as of" freshness |

Demo accounts exist only in the `dev` profile and the tests; a default start creates no accounts at all.

## Deliverables

| # | Deliverable | Where |
|---|---|---|
| 1 | Git history showing the development process | `git log`: block-by-block feature commits, then review-driven `fix:` commits |
| 2 | Working application, started locally | `docker compose up --build` (above) |
| 3 | AI working journal: asked / accepted / challenged / overrode | [`docs/AI_JOURNAL.md`](docs/AI_JOURNAL.md); curated, evidence-linked view: [`docs/DIRECTION_LOG.md`](docs/DIRECTION_LOG.md) |
| 4 | Supporting documentation | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/adr/`](docs/adr), [`docs/RUNBOOK.md`](docs/RUNBOOK.md), [`docs/PANEL_QA.md`](docs/PANEL_QA.md) |

**Panel questions** (why two services, Kafka down, concurrent assign, API contract, exposure staleness, rejected AI
code) are answered with code and test references in [`docs/PANEL_QA.md`](docs/PANEL_QA.md).

## Architecture

```
Angular (Material + Tailwind) ── nginx :4200 ──┬── /api/**         ──▶ claims-service :8080 (REST, JWT, state machine)
                                               │                       Postgres (claims, timeline, outbox)
                                               │                       outbox poller ──▶ Kafka topic claims.events
                                               │                                              │
                                               └── /api/reports/** ──▶ reporting-service :8081 ◀┘ (consumer, projections)
```

- **REST** for anything a user waits on (submit, every state transition, queries). **Kafka** for side effects that must
  not block the user (reporting today; notifications or fraud checks later).
- **Transactional outbox:** the claim change, its timeline row and its event commit in one transaction. If Kafka is down
  the approval still succeeds and the event is published later.
- **Idempotent, order-tolerant consumer:** duplicate events are ignored and an older claim version never overwrites a
  newer one, so the read model can be rebuilt by replaying the topic.
- **Claim lifecycle:** `SUBMITTED → UNDER_REVIEW ⇄ PENDING_INFO → APPROVED → SETTLED`, or `UNDER_REVIEW → REJECTED`.
  Invalid transitions and concurrent edits return RFC 7807 `409`.
- **Exposure** = sum of reserves on claims that are not settled or rejected, per market, type and currency (never
  summed across currencies). Reports carry an `asOf` timestamp because they lag the write side by a second or two.

Details and trade-offs: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md),
[ADR-0001](docs/adr/0001-modular-monolith-plus-reporting.md) (two services, not one or five),
[ADR-0002](docs/adr/0002-token-signing-and-oidc-path.md) (JWT today, OIDC path).

## Repository layout

```
claims-service/      Spring Boot 3, Java 21: domain + state machine, REST API, JWT, outbox, Flyway
reporting-service/   Spring Boot 3, Java 21: Kafka consumer, projections, reports, dead-letter ops
frontend/            Angular 18 + Material + Tailwind, nginx image, Playwright e2e suite
docs/                architecture, ADRs, runbook, panel Q&A, AI journal, direction log
scripts/e2e.sh       full-stack end-to-end run
docker-compose.yml   Postgres, Kafka, both services, frontend (all bound to 127.0.0.1)
```

## Tests

```bash
(cd claims-service && ./mvnw test)      # unit + Testcontainers integration (needs Docker)
(cd reporting-service && ./mvnw test)
scripts/e2e.sh                          # whole stack + Playwright in Docker (POSTGRES_PORT=55432 if 5432 is taken)
```

- **Backend:** state machine and domain rules, the full lifecycle over HTTP, concurrent assign (exactly one wins),
  ownership and role checks (incl. on a real server), outbox atomicity under a forced failure, idempotent and
  out-of-order event handling, poison messages, login lockout and rate limiting.
- **End-to-end** (`frontend/e2e`): claimant submit, the cross-role lifecycle, the dashboard catching up via Kafka, the
  pick-up race, lockout messaging, route guards, and no CSP violations. It runs with `docker-compose.e2e.yml`, which
  raises only the per-IP login limit (all test traffic shares one IP behind nginx).
- With Docker 29+, Testcontainers 1.21.4 or newer is required (pinned in the poms).

## Developing locally

Run the infrastructure in Docker and the services from your IDE with the `dev` profile:

```bash
docker compose up -d postgres kafka
(cd claims-service    && SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run)   # :8080, Swagger at /swagger-ui.html
(cd reporting-service && SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run)   # :8081
(cd frontend && npm install && npm start)                                     # :4200, proxies to both services
```

- `JWT_SECRET` (32+ bytes, the same for both services) is required outside the `dev` profile; the services refuse to
  start without it.
- Kafka listens on `kafka:19092` for containers and `localhost:9092` for your machine.
- If you ran an older version of this repo, reset local data once: `docker compose down -v` (the database is now
  named `lodge`, and the demo seed moved out of the default migrations).
- Behind a reverse proxy, set `SERVER_FORWARD_HEADERS_STRATEGY=native` and
  `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES=<your proxy IPs>` on claims-service so the login rate limit sees real
  client IPs. Compose already does this for the bundled nginx.

## Operations

- Login: 10 requests a minute per client IP, and a 15-minute lockout after 5 consecutive failures per email
  (`429` with `Retry-After`).
- Events that could not be published, and messages the consumer could not process, are dead-lettered rather than
  blocking anything. Both are exposed as metrics and can be listed, re-driven or discarded through manager-only ops
  endpoints. See [`docs/RUNBOOK.md`](docs/RUNBOOK.md) for alert rules, unlocking users and rebuilding the read model.

## Known gaps (deliberate)

- No FX conversion: exposure is reported per currency.
- HS256 shared-secret JWT for the demo; OIDC with JWKS is the documented production path (ADR-0002).
- Angular types are hand-written to mirror the backend DTOs, not yet generated from the OpenAPI spec.
- Updates use polling (10–15 s) rather than server-sent events.
