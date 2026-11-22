# Operations runbook

Commands assume the compose stack (`docker compose up`). Every command below was run against the real stack
unless marked otherwise. Kafka tools: `docker compose exec -T kafka /opt/kafka/bin/<tool> --bootstrap-server localhost:9092 ...`.
Postgres: `docker compose exec -T postgres psql -U claimdesk`.

## 1. Dead-lettered outbox rows (claims-service -> Kafka never succeeded)
Symptom: log line `Outbox event ... dead-lettered`, or reports missing a change.

```sql
SELECT id, aggregate_id, event_type, attempts, last_error, created_at FROM outbox_event WHERE dead_at IS NOT NULL;
```
1. Read `last_error` and fix the cause (e.g. record too large, topic misconfigured). Re-driving without a fix just
   dead-letters it again after 3 attempts.
2. Re-drive (covered by an integration test):
```sql
UPDATE outbox_event SET dead_at = NULL, attempts = 0, last_error = NULL WHERE id = '<id>';   -- or drop the WHERE for all
```
The publisher picks it up on its next tick (~1s). Consumers dedupe on `eventId` and ignore stale versions, so a
re-drive is always safe. If you decide an event is not worth sending, leave it: the next event for that claim is a full
snapshot and repairs consumers. Dead rows are never auto-deleted.

## 2. Dead-lettered consumer messages (reporting-service)
Bad messages land on `claims.events.DLT` (created on the first failure, so it may not exist yet).
1. Inspect: partition, offset, key and the exception headers (`kafka_dlt-exception-message`):
```bash
kafka-console-consumer.sh --topic claims.events.DLT --from-beginning --timeout-ms 6000 \
  --property print.partition=true --property print.offset=true --property print.key=true --property print.headers=true
```
2. Fix the cause (bad producer, outage, missing migration). Truly malformed messages cannot be fixed; leave them.
3. Re-drive ONE message by partition/offset (do not replay the whole DLT: unfixable messages would loop back):
```bash
kafka-console-consumer.sh --topic claims.events.DLT --partition <P> --offset <O> --max-messages 1 --timeout-ms 8000 \
  --property print.key=true --property key.separator='|' \
 | kafka-console-producer.sh --topic claims.events --property parse.key=true --property key.separator='|'
```
Keys are claim UUIDs, so per-claim ordering is preserved. Safe to repeat (idempotent consumer). The DLT has no
retention automation: delete the topic once handled.

## 3. Rebuild the whole read model
Stop reporting, truncate, rewind, start. Works while the topic still holds the history (default retention 7 days;
raise it if the read model must be rebuildable further back).
```bash
docker compose stop reporting-service
docker compose exec -T postgres psql -U claimdesk -c "TRUNCATE reporting.claim_projection, reporting.processed_event, reporting.known_officer"
kafka-consumer-groups.sh --group reporting-service --topic claims.events --reset-offsets --to-earliest --execute
docker compose start reporting-service
```
The group must be inactive for the reset to apply.

## 4. Retention knobs
| Setting | Default | What |
| --- | --- | --- |
| `claimdesk.outbox.retention-days` | 7 | published outbox rows deleted after this (dead/pending rows never) |
| `claimdesk.outbox.max-attempts` | 3 | non-retriable failures before dead-lettering |
| `reporting.processed-event-retention-days` | 7 | duplicate-guard rows kept; pruning is safe because upserts are version-guarded |
