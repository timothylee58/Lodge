# Technical debt register

Assessed 22 Nov 2026 against the brief's technology preferences. Scoring: **Priority = (Impact + Risk) × (6 − Effort)**,
each 1–5 (Effort 1 = trivial). Effort in rough hours. Items marked *(review)* came from the code review of the whole
history (`e136f69..HEAD`) on the same day.

## Fit with the preferred technology

| Preference | Lodge | Verdict |
|---|---|---|
| Frontend: Angular | Angular 18, standalone components, signals, Material + Tailwind, lazy routes | Meets it. No component-level tests yet (#5). |
| Backend: Java/Spring Boot (or .NET) | Java 21, Spring Boot 3.3, two services | Meets it. Boot 3.3 is past open-source support (#3). |
| REST/HTTP and Kafka, with a reasoned split | REST for anything a user waits on; Kafka (outbox) for reporting side effects; rule in ADR-0001 | Meets it and is explained. Outbox assumes one instance (#7). |
| Database, caching, testing, API tooling, frontend architecture: free choice | Postgres + Flyway; no cache (read model is the cache); JUnit + Testcontainers + Playwright; springdoc OpenAPI | Sound choices. API tooling stops at publishing the spec (#2). |

## Register (highest priority first)

| # | Item | Type | I | R | E | Priority | ~Hours | Why it matters |
|---|---|---|---|---|---|---|---|---|
| 1 | No CI pipeline: tests only run on a developer's machine | Infrastructure / test | 4 | 4 | 2 | **32** | 3 | Every safeguard in this repo (30+ backend tests, e2e) only protects code if it runs on every push. GitHub Actions with Docker can run all three suites. |
| 2 | Angular types hand-written, not generated from OpenAPI | Code / architecture | 3 | 4 | 2 | **28** | 3 | The backend owns the contract but nothing enforces it at build time; a renamed field breaks the UI silently until e2e runs. Generate `models.ts` from `/v3/api-docs` in CI. |
| 3 | ~~SLA semantics~~ **fixed** *(review)*: decision clock resets after each info request; banner counts claimant-side waits as officer "overdue" | Code | 3 | 4 | 2 | **28** | 3 | The feature exists to judge officer workload fairly; both bugs mis-measure exactly that. |
| 4 | ~~Login rate-limiter map unbounded~~ **fixed** *(review)* | Code / security | 2 | 3 | 1 | **25** | 1 | A cheap memory-pressure attack on the login endpoint. Hard cap or /64 keying. |
| 5 | No Angular component/unit tests (only Playwright e2e + helper tests) | Test | 3 | 3 | 2 | **24** | 5 | E2E is slow (needs the whole stack) and coarse; guards, interceptor, polling and form logic have no fast tests. |
| 6 | Observability is half-built: metrics exist but no Prometheus registry, no alert delivery, no compose healthchecks for the services, no correlation id in logs | Infrastructure | 3 | 3 | 2 | **24** | 4 | Runbook alert rules can't fire as shipped; incidents would be diagnosed from raw logs. |
| 7 | Outbox publisher assumes a single claims-service instance *(review)* | Architecture | 2 | 4 | 2 | **24** | 3 | Scaling to two replicas double-publishes every event. Consumers stay correct, but it's a trap for whoever scales it. `FOR UPDATE SKIP LOCKED` or a leader lock. |
| 8 | Dependencies past support: Spring Boot 3.3.x, Angular 18, Node 20 build image (verify dates against the official EOL pages) | Dependency | 3 | 4 | 3 | **21** | 8 | Unsupported versions stop receiving security fixes. Upgrade Boot within 3.x and Angular one major at a time; tests are in place to catch regressions. |
| 9 | ~~Claimant timeline exposes staff user ids~~ **fixed** *(review)* | Code / security | 1 | 2 | 1 | **15** | 0.5 | Small information leak; trivial to null for claimants. |
| 10 | Frontend image uses `npm install`, not `npm ci` (npm bug workaround) | Dependency | 1 | 2 | 1 | **15** | 1 | Builds aren't strictly reproducible from the lockfile. Retry `npm ci` on a newer npm. |
| 11 | JWT: shared HS256 secret and duplicated verifier in both services | Architecture | 2 | 4 | 3 | **18** | 6 (RS256) | Reporting can mint tokens for claims-service. ADR-0002 covers the OIDC path; RS256 is the cheap step. |
| 12 | Large integration test classes (486 and 295 lines, shared database state) | Test | 2 | 2 | 2 | **16** | 3 | Order-sensitive assertions (`before + 1`) and slow feedback; split by concern with per-test data. |
| 13 | ~~Queue summary counted in Java~~ **fixed: one SQL aggregate** *(review)* | Code / performance | 2 | 2 | 2 | **16** | 2 | Fine at demo scale, slow at tens of thousands of open claims. SQL aggregate. |
| 14 | Reporting shares the Postgres instance (own schema) | Architecture | 1 | 2 | 3 | **9** | 2 | Acceptable locally; a separate database or store is the production shape. Documented. |
| 15 | Polling instead of server-sent events | Architecture | 2 | 1 | 3 | **9** | 6 | Up to 10–15 s UI lag and extra load; acceptable for the MVP. |
| 16 | Several overlapping docs (journal, direction log, decomposition, panel Q&A) can drift | Documentation | 1 | 1 | 1 | **10** | 1/month | Keep the README as the index; review docs when behaviour changes. |

Not debt (deliberate, documented): no FX conversion, no caching layer, demo-only seed data, no public hosting.

## Remediation plan (alongside feature work)

**Phase 1 — next sprint, about 1.5 days.** Items 1, 3, 4, 9, plus the queue's lost incident-date column (review nit).
Do CI first so every later fix is checked automatically.

**Phase 2 — following sprint, about 3 days.** Items 2, 5, 6, 7. Contract generation and component tests change how the
frontend is developed, so pair them; observability and the outbox lock make a second replica safe.

**Phase 3 — scheduled, about 3 days.** Items 8, 10, 11, 13. Upgrades land one major at a time behind the CI from phase 1.
Items 12, 14, 15, 16 are taken when the code around them is next touched.

**Rule for new work:** a feature PR that touches an item's code fixes or narrows that item, and CI must stay green.

## Also fixed (23 Nov 2026)
- V6 backfill used `updated_at` (moves on reserve changes): **V8** recomputes `status_changed_at`, banked review time
  and the SLA clock from the claim timeline. Idempotent; regression test runs it against a deliberately wrong row.
- Found while writing V8: the first SLA-pause fix double-counted review time on a second info request. Fixed (assign,
  not add) with a two-cycle test.
- Found by the stronger claimant-redaction test: the claim summary also sent claimants the officer's id and name.
  Claimants now see "A claims officer".
