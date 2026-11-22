# ADR 0001: Modular monolith (claims) + separate reporting consumer

## Status
Accepted

## Context
5-hour cap. Brief requires REST + Kafka and asks for justified service boundaries.

## Decision
- claims-service owns all writes and the claim state machine (REST).
- reporting-service consumes claims.events and builds workload/exposure read models.
- Events published via transactional outbox, keyed by claimId.

## Consequences
+ One clear boundary (write vs read), real Kafka use, small surface to test.
+ Reporting can be rebuilt by replaying the topic.
- Dashboard is eventually consistent (seconds).
- Outbox poller adds latency (~1s) and needs idempotent consumers.
