# How I directed, challenged and overrode the AI — Lodge build

Audience: Talent Acquisition / interview panel. Companion to `docs/AI_JOURNAL.md` (the raw, entry-by-entry log).
Every claim below points to a commit, a test or a file in this repo so it can be checked.

**How to read this honestly.** I directed scope, order, quality bar and which trade-offs to accept. Many concrete
bugs were found by the AI's own verification steps — but those steps exist because I required evidence (tests,
real-server runs, a self-review) before moving on. Section 2 separates the two.

---

## 1. Decisions I made (direction and overrides)

| # | What I decided | Why it mattered | Evidence |
|---|---|---|---|
| D1 | Wrote the PRD first: modular monolith + one Kafka consumer, REST-vs-Kafka rule, outbox, time-boxed blocks, journal format. | Fixed the architecture before any code, so the AI built *my* design. | PRD; `docs/adr/0001-…` |
| D2 | **Overrode the AI's in-progress scaffold** mid-build with my own (infra-only compose, my schema, my seed users, my package layout). | The AI had started a different full-stack layout; I kept control of structure. | commit `ab6b6b1` |
| D3 | Shipped my scaffold with a **"don't blindly accept" checklist**: verify the BCrypt hash, check JSONB mapping, check `ddl-auto: validate`, outbox stalls behind a bad row, `X-User-Id` is an auth bypass. | Every item became a verified fix, not an assumption (below). | see D3a–e |
| D3a | BCrypt seed hash | Regenerated locally, proven by real logins. | `R__demo_seed.sql`, integration tests log in |
| D3b | JSONB mapping | Verified on Postgres: `payload->>'status'` returns the field, not a double-encoded string. | `docs/AI_JOURNAL.md` smoke findings |
| D3c | Outbox stall | Retriable vs non-retriable failures; dead-letter after 3; 10-tick outage test. | `bc54e7b`, `OutboxPublisherTest` |
| D3d | `X-User-Id` bypass | Replaced by JWT + ownership checks before any demo. | `ab6b6b1` |
| D4 | **Gated the build block by block** ("Yes, block 3/4/5") and demanded **"fix and commit first"** before block 4. | Clean checkpoints; no half-finished block carried forward. | commit sequence in `git log` |
| D5 | For each open-items list, **chose fix vs describe**: fixed alerting, lockout, retention, compose; told the AI to *describe, not build* OIDC/JWKS. | Spent the time budget where the risk was; documented the rest as a decision. | `000d25e`, `docs/adr/0002-…` |
| D6 | **Ran a code review on my own codebase** and made the AI fix all 5 high findings, then the 2 mediums. | Treated AI output as untrusted until reviewed. | `3f90e4a`, `8180562`, `b2f140d` |
| D7 | Asked for Angular Material + Tailwind and this documentation. | Matches the brief's stack; forced a real integration problem to solve (below). | Block 5b in journal |
| D8 | Asked for automated frontend tests instead of accepting "verified manually". | Closed the last listed gap; the suite was proven able to fail (mutation check). | `frontend/e2e`, `scripts/e2e.sh` |

## 2. AI mistakes that verification caught (and the override)

Each one would have shipped if I had accepted "it compiles / the tests pass".

| Where | What the AI produced | How it was caught | Override | Evidence |
|---|---|---|---|---|
| Login | "Dummy" BCrypt hash for unknown emails was malformed -> instant reject -> **user enumeration by timing**. | Read-through before running. | Encode a real throwaway hash at startup; test asserts identical 401 bodies. | `AuthService`, `authGates` test |
| Security | Role-denied requests returned **401 instead of 403** on a real server (MockMvc hid it). | Ran both services for real. | Set status directly in an access-denied handler; added real-server tests in both services (red before, green after). | `c87537a` |
| Consumer | First instinct: retry a bad Kafka message forever -> **one message wedges a partition**. | Design challenge. | Dead letters + non-retryable poison exception. | `70ba9f0` |
| Review | **Unicode lockout bypass**: a dotless-ı spelling of a locked email logged in (200). | Proven in a throwaway copy before fixing. | ASCII-only emails (API + DB constraint), exact `lower(email)` lookup. Rejected "throttle by user id" because it re-enables account enumeration. | `8180562` |
| Review | **Demo MANAGER account with password "password" in the default migrations** = backdoor anywhere. | Code review. | Seed moved to a dev/test-only Flyway location; test proves a default boot has zero users. | `8180562`, `ProductionMigrationsTest` |
| Review | Dead-letter recorder failed on a 300-char key -> **partition stalled, 0 dead letters**. | Proven in a throwaway copy. | TEXT columns, NUL sanitising, coordinates-only fallback. | `3f90e4a` |
| Fix of the fix | Fallback caught `DataIntegrityViolationException`, but Postgres's NUL error arrives as `DataAccessResourceFailureException` — the same family as "DB down". | Integration test failed. | Classify by **SQLState** (22/23 = bad data, else rethrow). | `DeadLetterRecorderTest` |
| Dates | "Incident in the future" judged on **server UTC**: a 04:00 claim in KL for that day was rejected. | Code review. | Per-market time zone + injected Clock. | `b2f140d`, `todayIsPerMarketNotServerUtc` |
| UI | Production page **rendered unstyled**: Angular's critical-CSS inlining uses an inline `onload`, blocked by my CSP. | Browser check. | Disabled inlining instead of weakening the CSP. | `a63b714` |
| UI | Material + Tailwind: borders vanished; the first fix then **broke Material's text-field outline**. | Screenshots + computed styles. | Scoped the border rule to Tailwind border classes only. | Block 5b journal |
| Process | A zsh glob aborted a command so a password hash **silently stayed stale**; a script assertion and a test email were wrong. | Re-reading outputs, not trusting exit codes. | Logged as process slips. | `docs/AI_JOURNAL.md` |

## 3. Trade-offs I accepted on purpose

- Exposure per market **and currency**, no FX (never sums currencies).
- HS256 shared secret for the demo; OIDC/JWKS described in ADR-0002, not built.
- Polling (10–15 s) with a visible "as of" line instead of SSE.
- `npm install` instead of `npm ci` in the image (npm bug with this lockfile), noted in the Dockerfile.
- Material roughly doubles the initial bundle (92 -> 164 kB transferred); accepted for the brief's stack.
- E2E tests run against a stack with a raised per-IP login limit (one shared IP behind nginx); lockout tested as-is.

## 4. Challenges to run live in an interview

Start: `docker compose up --build` -> http://localhost:4200 (demo users in `README.md`).

| Challenge for me | What I show |
|---|---|
| "Show a line of AI code you rejected." | `AuthService` dummy-hash fix and the `authGates` test (D-table row 1). |
| "Two officers click Pick up at once." | `concurrentAssignExactlyOneWins` (one 200, one 409); in the UI the loser sees "picked up by someone else". |
| "Kafka is down when a claim is approved." | `docker compose stop kafka`, approve in the UI, `/actuator/metrics/lodge.outbox.oldest.pending.seconds` grows; start Kafka, the dashboard catches up. |
| "Someone sends garbage to the topic." | Produce a bad message (`docs/RUNBOOK.md` §2), list it in `/api/ops/dead-letters`, re-drive -> 422, discard. |
| "How stale can the dashboard be?" | The "Data as of" line; ARCHITECTURE "Consistency and staleness". |
| "Brute-force a login." | 6 wrong attempts -> 429 + `Retry-After`; try the dotless-ı spelling -> 400. |
| "Why two services, not one or five?" | ADR-0001 + the REST-vs-Kafka rule. |
| "Where did the AI get it wrong?" | Section 2 of this file, each with a commit. |
| "How do you know your UI tests test anything?" | Break the 409 message in `workbench.component.ts`, run `scripts/e2e.sh`: exactly the race test fails. |
