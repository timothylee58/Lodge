# Lodge architecture

```
claims-service (write side, system of record)          reporting-service (read side, may lag)
  REST + JWT + state machine                             Kafka consumer -> projections -> REST /api/reports/*
  Postgres schema: public                                Postgres schema: reporting (own tables only)
  claim + claim_event + outbox_event  --one tx-->  outbox poller --> Kafka topic claims.events (key = claimId)
```

## Frontend
Angular 18 standalone components and signals, served by nginx, which proxies `/api/reports` and dead-letter ops to
reporting-service and the rest of `/api` to claims-service. nginx sets a strict CSP (no inline scripts), so Angular's
critical-CSS inlining is turned off (it relies on an inline `onload` handler). The frontend container has a fixed IP
and is claims-service's only trusted proxy for `X-Forwarded-For`. Route guards are UX only; every rule is enforced
server-side. Tokens live in sessionStorage (XSS-exposed; mitigated by CSP and template escaping; OIDC is the
production path, ADR-0002). Async updates use polling (10–15s); SSE is the next step.

## REST vs Kafka rule
- **REST**: anything a user waits on and needs an immediate yes/no for: submit, every state transition, queries.
  Validation errors and the new state must come back in the same response.
- **Kafka**: side effects that other parts of the system need but that must not block the user: reporting
  projections today; notifications and fraud checks later. Loosely coupled and replayable.

## Event contract (`claims.events`)
Key = `claimId` (per-claim ordering). Value = JSON **full snapshot** of the claim after the change:
`eventId, eventType, occurredAt, claimId, claimNumber, version, status, market, type, currency,
reserveAmount, settledAmount, assigneeId, assigneeName`. Consumers ignore unknown fields.
Snapshots (not deltas) mean a lost or skipped event is repaired by the next one for that claim.

## Delivery guarantees
- **Producer**: transactional outbox. Claim change, timeline row and outbox row commit together or not at all
  (tested, incl. a forced serialisation failure). The poller sends outside any DB transaction; at-least-once.
  Retriable failures (broker down) wait and retry; non-retriable ones are dead-lettered after 3 attempts so a
  poison row cannot block the queue (`outbox_event.dead_at`).
- **Consumer**: idempotent and order-tolerant. `processed_event` rejects exact redeliveries; the projection upsert
  only applies an event whose claim `version` is newer than the stored one, so late events cannot move a claim
  backwards. Both happen in one transaction. Unparseable/invalid messages are stored in `dead_letter` immediately
  (queryable, re-drivable or discardable via an ops API, exposed as a metric); transient DB errors retry twice first.
  The recorder sanitises what Postgres cannot store (NUL, oversize fields); data still rejected (SQLState 22/23) is
  stored as coordinates only. Only database outages propagate: the offset is not committed and the record is redelivered.

## Consistency and staleness
Reports are eventually consistent. Typical lag = outbox poll (1s) + consumer (sub-second). Every report returns
`asOf` (newest event reflected) and `generatedAt`; the UI shows "as of {asOf}". If Kafka or reporting is down the
write side keeps working and the outbox accumulates; reports catch up on recovery.

## Rebuilding the read model
See `docs/RUNBOOK.md` section 3 (verified): truncate projections, reset the consumer group to earliest.
Possible because the projection depends only on the events.

## Operability
Dead outbox rows and consumer dead letters are metrics (`lodge.outbox.dead`, `reporting.dead_letters.unresolved`,
plus outbox lag) and have MANAGER-only list / re-drive / discard endpoints. Procedures and alert rules: `docs/RUNBOOK.md`.

## Security
JWT (HS256, shared secret, no default; app refuses to start without `JWT_SECRET`). Roles: CLAIMANT, OFFICER,
MANAGER. Role gates are URL-level; ownership ("my claim", "assigned to me") is enforced in services. Claimants get
404 for others' claims and never see reserve figures. Reports and ops endpoints: MANAGER only; `/actuator` other than health too. Login is protected by a per-IP rate limit and a per-email lockout (see ADR-0002). Emails are printable ASCII
(validated and enforced by a DB constraint) so the throttle key and the account lookup are always the same string.
`X-Forwarded-For` is used only from configured trusted proxies. Demo accounts exist only in `db/demo-seed` (dev/tests).
Production path: OIDC (Keycloak / Azure AD) with JWKS instead of a shared secret: `docs/adr/0002-token-signing-and-oidc-path.md`.

## Dates and errors
Incident dates are validated against "today" in the policy market's time zone. Business-rule violations are
`InvalidRequestException` (400); unexpected exceptions are generic 500s.

## Known gaps (deliberate)
- Exposure is per market and currency with per-currency totals; **no FX conversion** (never sums across currencies).
- Workload lists officers once they have been assigned a claim (roster learned from events); a brand-new officer with
  no assignment yet is invisible to reporting. Fix = publish user lifecycle events.
- Token signing uses a shared HS256 secret and duplicated verifier code; OIDC/JWKS is the production path (ADR-0002).
- Login rate limit is per instance and in memory; lockout can be used to lock a known email out (bounded by the per-IP
  limit). No MFA, no refresh tokens, no password reset. All of this goes away with an IdP (ADR-0002).
- Alerting: metrics, re-drive tooling and example rules exist; wiring them to a pager is not built or tested.
- Re-drive actor is only logged; there is no durable audit table for operator actions.
- `processed_event` and published `outbox_event` rows are pruned after 7 days (configurable).
