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
