# Decomposing the problem

How Lodge answers the brief's seven decomposition questions. Each answer says what was built, why, and what is
deliberately not built. File references point at the code that implements it.

---

## 1. What are the core entities in this domain? How do they relate?

```
User (role: CLAIMANT | OFFICER | MANAGER, market)
 │ 1
 │ holds *
Policy (type MOTOR|PROPERTY, market, coverageLimit, currency, status)
 │ 1
 │ is claimed against by *
Claim ── claimant ─▶ User          (who lodged it)
  │   ── assignee ─▶ User?         (the officer working it; null while unassigned)
  │   status, reserveAmount, settledAmount, currency, version
  ├─ 1..* ClaimEvent   (append-only audit trail; also the claimant's timeline)
  ├─ 0..* InfoRequest  (officer question -> claimant answer; at most one OPEN at a time)
  └─ 1..* OutboxEvent  (the same change, as a message waiting to go to Kafka)
```

- **Claim is the aggregate root.** Every change to a claim, its timeline row and its outbox row happen in one
  transaction through one service (`ClaimCommandService`), so they can never disagree.
- **A claim copies `market`, `type` and `currency` from its policy at submission.** Reporting and permissions need
  them without joining back to the policy, and a later policy edit must not rewrite an existing claim.
- **Reserve vs settlement:** the reserve is the amount set aside while the claim is open (it *is* the exposure); the
  settlement is what was actually paid. Both are capped at the policy's coverage limit.
- **Read side (reporting-service) has its own entities**, built only from events: `claim_projection` (one row per
  claim, latest version), `known_officer` (roster learned from assignments), `processed_event` (duplicate guard),
  `dead_letter`.
- Schema: `claims-service/src/main/resources/db/migration/V1__init.sql`; read model:
  `reporting-service/src/main/resources/db/migration/`.

## 2. Is the backend one service or more than one? What drives that decision?

**Two:** `claims-service` (write side, system of record) and `reporting-service` (read side, analytics).

The decision is driven by **consistency requirements**, not by the number of entities:

| | claims-service | reporting-service |
|---|---|---|
| Must be correct *now* | yes — the user is waiting for "approved" or "409" | no — a manager can read figures a second old |
| Data it owns | users, policies, claims, timeline, outbox | projections derived from events |
| Can be rebuilt | no (source of truth) | yes, by replaying the topic |
| Load shape | small transactional writes | aggregate queries over every claim |

- **Not one service:** heavy aggregate queries would compete with transactional writes, and reporting would be coupled
  to the write schema.
- **Not five** (one per entity): claims, info requests and the timeline change together in one transaction; splitting
  them means distributed transactions and more plumbing, with no team or scaling boundary to justify it.
- Record: [`adr/0001-modular-monolith-plus-reporting.md`](adr/0001-modular-monolith-plus-reporting.md).

## 3. What operations need to be synchronous? What can be event-driven?

**Rule: synchronous if a person is waiting for a yes/no answer; event-driven if it is a side effect others need
but that must not block that person.**

| Synchronous (REST) | Event-driven (Kafka `claims.events`) |
|---|---|
| Login | Exposure, workload and status-by-market projections |
| Submit a claim (validation, policy ownership, date) | *Future:* notifications to claimants |
| Every state transition: assign, request info, respond, approve, reject, settle, adjust reserve | *Future:* fraud checks, SLA timers |
| All reads of claims and the queue | |

- Transitions are synchronous because their answer *is* the product: the new state, or a 409/400 the user can act on.
- Events carry a **full snapshot** of the claim plus its `version`, so a consumer never calls back into
  claims-service, and a later event repairs any missed one.
- The **transactional outbox** bridges the two: the synchronous transaction writes the event row; a poller publishes
  it. If Kafka is down, approvals still succeed and events are published on recovery.

## 4. What is the contract between frontend and backend, and who defines it?

- **The backend owns the contract.** Controllers and DTO records are the source of truth, published as OpenAPI by
  springdoc (`:8080/v3/api-docs`, Swagger UI at `/swagger-ui.html`; reporting at `:8081`).
- **Shape of the contract:**
  - Resource reads (`GET /api/claims/{id}` returns the claim, its timeline and its info requests).
  - **Verb-style commands for transitions** (`POST /api/staff/claims/{id}/approve`), never `PATCH status`, so business
    rules stay on the server and the frontend cannot invent an illegal transition.
  - Errors are **RFC 7807 ProblemDetail** everywhere (400 rule broken, 403 not yours, 404 not visible to you,
    409 invalid transition or concurrent edit, 429 throttled with `Retry-After`).
  - Role-shaped responses: claimants never receive reserve figures or reserve-change events.
- **Honest gap:** the Angular types (`frontend/src/app/core/models.ts`) are hand-written to mirror the DTOs, not yet
  generated from the spec. Contract drift is caught at runtime by the end-to-end suite. Next step: generate the types
  from `/v3/api-docs` in CI so a breaking backend change fails the frontend build.

## 5. How do you handle the async nature of claims processing across both tiers?

Claims processing is asynchronous in two ways, and each tier handles both.

**Human asynchrony** (a claim waits for an officer, or for the claimant to answer):
- The state machine models waiting explicitly (`SUBMITTED`, `PENDING_INFO`) instead of hiding it.
- The claimant's page shows progress steps and a timeline, and **polls every 10 s**; the officer's workbench polls every
  15 s. A failed poll shows an error but keeps polling.

**System asynchrony** (reports lag the write side):
- Outbox to Kafka to consumer, with at-least-once delivery made safe by an idempotent, version-guarded consumer.
- Every report returns `asOf` (newest event reflected) and the dashboard says "Data as of …" and that it can lag.
- Failures never block the pipeline: unpublishable events and unprocessable messages are dead-lettered, alerted on via
  metrics, and re-driven from an ops API.

**Concurrency between people:** optimistic locking (`@Version`) plus the state machine means two officers picking up
the same claim get one 200 and one 409, and the UI explains the 409 and refreshes.

Next step: server-sent events instead of polling.

## 6. What does a claims officer need to see to manage their workload effectively?

**Built:**
- A **queue** with three views: *Unassigned* (oldest first, so nothing starves), *Assigned to me*, and *All*, with a
  status filter and pagination. One click to pick up.
- A **workbench** per claim showing only the actions valid in its current state, the reserve, the info-request
  thread, and the full timeline.
- Clear answers when something changed underneath them (another officer picked it up, the claim moved on).
- The manager's dashboard shows **workload per officer** (reviewing / waiting for info / approved / total open) and how
  many claims are waiting for an officer.

**Not built, and what I would add next:**
- **Claim age and SLA indicators** (days open, days waiting on the claimant), with the queue sortable by them.
- Filtering by the officer's **market** (officers can currently see every market's claims).
- Reassignment by a manager, for when an officer is away.
- A personal count on the officer's own screen ("you have 3 waiting for your decision").

## 7. What does the system need to know about outstanding liability exposure?

- **Per claim:** the current **reserve** (set on approval, adjustable while open), its **currency**, its **market** and
  **type**, and whether it is still **open**. Exposure = Σ reserve of claims not `SETTLED` or `REJECTED`.
- **Over time:** every reserve change is an event (`RESERVE_CHANGED`, with old and new values), so exposure history
  can be reconstructed from the timeline.
- **Limits:** the policy's coverage limit caps both reserve and settlement.
- **Freshness:** when the figure was last updated (`asOf`), because it comes from an eventually consistent read model.
- **Reported as:** totals per currency, then per market and type (`/api/reports/exposure`). Currencies are **never
  added together**.

**Known gaps:**
- No FX conversion, so there is no single group-wide total (it would need dated rates and a reporting currency).
- No exposure trend or ageing report yet; the events to build them already exist.
