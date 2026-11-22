# Operations runbook

Commands assume the compose stack (`docker compose up`). Every command below was run against the real stack
unless marked otherwise. Kafka tools: `docker compose exec -T kafka /opt/kafka/bin/<tool> --bootstrap-server localhost:9092 ...`.
Postgres: `docker compose exec -T postgres psql -U claimdesk`.

## 0. Getting a manager token
```bash
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"mark@demo.local","password":"password"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
```
Ops endpoints and `/actuator/metrics/*` are MANAGER-only on both services (health is public). All commands below
were run against the real stack, and the same behaviour is covered by integration tests.

## 1. Dead-lettered outbox rows (claims-service could not publish to Kafka)
Symptom: metric `claimdesk.outbox.dead` > 0, or log line `Outbox event ... dead-lettered`.
```bash
curl -s localhost:8080/api/ops/outbox/dead -H "Authorization: Bearer $TOKEN"        # id, eventType, attempts, lastError
```
1. Read `lastError` and fix the cause first (record too large, topic misconfigured...). Re-driving without a fix just
   dead-letters the row again after 3 attempts.
2. Re-drive one, or all (up to 100 per call, oldest first):
```bash
curl -s -X POST localhost:8080/api/ops/outbox/<id>/redrive -H "Authorization: Bearer $TOKEN"
curl -s -X POST localhost:8080/api/ops/outbox/redrive-all  -H "Authorization: Bearer $TOKEN"
```
The publisher picks the row up within ~1s. Re-driving is always safe: consumers dedupe on `eventId` and ignore stale
versions, so an old event arriving late cannot move a claim backwards. The actor is logged. If you decide an event is
not worth sending, leave it: the next event for that claim is a full snapshot and repairs consumers.
SQL fallback (API unavailable): `UPDATE outbox_event SET dead_at = NULL, attempts = 0, last_error = NULL WHERE id = '<id>';`

## 2. Dead letters in reporting-service (messages the consumer gave up on)
Symptom: metric `reporting.dead_letters.unresolved` > 0, or log `Dead-lettered claims.events-<p>@<offset>`.
Poison messages are stored in table `dead_letter` immediately; transient failures retry twice first.
```bash
curl -s localhost:8081/api/ops/dead-letters -H "Authorization: Bearer $TOKEN"       # payload, error, partition/offset
curl -s -X POST localhost:8081/api/ops/dead-letters/<id>/redrive  -H "Authorization: Bearer $TOKEN"   # 200 applied, 422 still invalid
curl -s -X POST localhost:8081/api/ops/dead-letters/redrive-all   -H "Authorization: Bearer $TOKEN"
curl -s -X POST localhost:8081/api/ops/dead-letters/<id>/discard  -H "Authorization: Bearer $TOKEN"   # give up deliberately
```
Re-drive runs the stored payload through the same parser and idempotent projection as the live consumer (no Kafka round
trip), so repeating it is harmless. A message that is still invalid returns 422 and stays listed with `lastError` and a
bumped `redriveAttempts`; discard it once you are sure it cannot be fixed (e.g. genuinely malformed). Resolved rows are
kept for audit. A row with an empty payload and an error starting `Unrecordable message` could not be stored as data;
read the original from Kafka by partition/offset (`kafka-console-consumer.sh --topic claims.events --partition <P>
--offset <O> --max-messages 1`) while it is within retention, then discard the row. If recording a dead letter itself fails (DB down) the offset is not committed and the record is
redelivered, so nothing is dropped.

## 3. Rebuild the whole read model
Stop reporting, truncate, rewind, start. Works while the topic still holds the history (default retention 7 days;
raise it if the read model must be rebuildable further back).
```bash
docker compose stop reporting-service
docker compose exec -T postgres psql -U claimdesk -c "TRUNCATE reporting.claim_projection, reporting.processed_event, reporting.known_officer"
kafka-consumer-groups.sh --group reporting-service --topic claims.events --reset-offsets --to-earliest --execute
docker compose start reporting-service
```
The group must be inactive for the reset to apply. `dead_letter` is intentionally not truncated: replays re-hit
the same (topic, partition, offset) so already-discarded messages are not resurrected.

## 4. Locked-out user
Lockout is 5 consecutive failures -> 15 minutes (any email, real or not). It clears on a successful login or after the
lock expires. To unlock someone now (after verifying who is asking):
```sql
DELETE FROM login_throttle WHERE email = 'user@example.com';
```
Many locked emails at once, or many 429s from one address, is a spray/brute-force signal: look at `login_throttle`
(`SELECT email, failed_attempts, locked_until FROM login_throttle WHERE locked_until > now()`) and your proxy logs.

## 5. Alerting
Signals (Micrometer, visible at `/actuator/metrics/<name>`; with a Prometheus registry they appear as `_` names):

| Metric | Meaning | Suggested alert |
| --- | --- | --- |
| `claimdesk.outbox.dead` | outbox rows needing an operator | `> 0` for 1m -> page/ticket |
| `claimdesk.outbox.oldest.pending.seconds` | age of oldest unpublished row | `> 120` for 2m -> publishing stuck (broker down?) |
| `claimdesk.outbox.pending` | publish backlog | rising trend |
| `reporting.dead_letters.unresolved` | consumer dead letters | `> 0` for 1m -> ticket |
| `reporting.dead_letters.recorded` | new dead letters since start | `increase(...[5m]) > 0` |

Example rules (**not run in this repo**: add `micrometer-registry-prometheus`, expose `/actuator/prometheus` on a
management port, and load these into Prometheus/Alertmanager):
```yaml
- alert: ClaimdeskOutboxDead
  expr: claimdesk_outbox_dead > 0
  for: 1m
- alert: ClaimdeskOutboxStuck
  expr: claimdesk_outbox_oldest_pending_seconds > 120
  for: 2m
- alert: ReportingDeadLetters
  expr: reporting_dead_letters_unresolved > 0
  for: 1m
```
This repo ships the signals and the re-drive tooling; routing alerts to a pager is environment-specific and not built.

## 6. Retention and auth knobs
| Setting | Default | What |
| --- | --- | --- |
| `claimdesk.outbox.retention-days` | 7 | published outbox rows deleted after this (dead/pending rows never) |
| `claimdesk.outbox.max-attempts` | 3 | non-retriable failures before dead-lettering |
| `reporting.processed-event-retention-days` | 7 | duplicate-guard rows kept; pruning is safe because upserts are version-guarded |
| `claimdesk.auth.rate-limit-per-minute` | 10 | login requests per client IP per minute (429 + Retry-After) |
| `claimdesk.auth.max-failures` / `lock-minutes` | 5 / 15 | consecutive failures per email before lockout |
