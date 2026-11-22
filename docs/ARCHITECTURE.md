# ClaimDesk architecture

```
claims-service (write side, system of record)          reporting-service (read side, may lag)
  REST + JWT + state machine                             Kafka consumer -> projections -> REST /api/reports/*
  Postgres schema: public                                Postgres schema: reporting (own tables only)
  claim + claim_event + outbox_event  --one tx-->  outbox poller --> Kafka topic claims.events (key = claimId)
```

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
  backwards. Both happen in one transaction. Unparseable/invalid messages go to `claims.events.DLT` immediately;
  transient DB errors retry twice first.

## Consistency and staleness
Reports are eventually consistent. Typical lag = outbox poll (1s) + consumer (sub-second). Every report returns
`asOf` (newest event reflected) and `generatedAt`; the UI shows "as of {asOf}". If Kafka or reporting is down the
write side keeps working and the outbox accumulates; reports catch up on recovery.

## Rebuilding the read model
Truncate `claim_projection` and `processed_event`, then reset the consumer group's offsets to earliest
(`kafka-consumer-groups.sh --group reporting-service --topic claims.events --reset-offsets --to-earliest --execute`).
Possible because the projection depends only on the events.

## Security
JWT (HS256, shared secret, no default; app refuses to start without `JWT_SECRET`). Roles: CLAIMANT, OFFICER,
MANAGER. Role gates are URL-level; ownership ("my claim", "assigned to me") is enforced in services. Claimants get
404 for others' claims and never see reserve figures. Reports: MANAGER only. Production path: OIDC (Keycloak /
Azure AD) with JWKS instead of a shared secret.

## Known gaps (deliberate)
- Exposure is per market **and currency**, no FX conversion.
- Workload lists only officers who currently hold open claims (reporting has no user table).
- `processed_event` grows unbounded; prune to the topic retention.
- JWT shared secret and duplicated verifier code across services; a shared lib / JWKS is the next step.
- No refresh tokens, rate limiting or account lockout on login.
- Dead-lettered events (both sides) need an operator re-drive; none is built.
