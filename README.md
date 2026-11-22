# ClaimDesk

Claims intake and handling: claimants report incidents and track them, officers work a queue, managers see exposure.
Backend-first; see `docs/adr/0001-modular-monolith-plus-reporting.md` and `docs/AI_JOURNAL.md`.

## Run locally (claims-service)

```bash
docker compose up -d                      # Postgres + Kafka (infra only)
cd claims-service
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run   # or run from your IDE with that env var
open http://localhost:8080/swagger-ui.html
```

`JWT_SECRET` (>= 32 bytes) is **required**; the app refuses to start without it. The `dev` profile supplies a
throwaway local value (`application-dev.yml`). Anywhere else, set `JWT_SECRET` in the environment.

Demo users (password `password`): `aina@demo.local` (claimant), `wei@demo.local` / `priya@demo.local` (officers),
`mark@demo.local` (manager). `POST /api/auth/login` returns a bearer token.

Ports: Postgres `5432` and Kafka `9092` are published; if 5432 is already in use on your machine, remap it in
`docker-compose.yml` and in `spring.datasource.url`.

## Tests

```bash
cd claims-service && ./mvnw test
```

Integration tests use Testcontainers (real Postgres + Kafka) and need Docker. With Docker 29+, Testcontainers
>= 1.21.4 is required (pinned in the pom).

## Operations notes

- Outbox rows that fail with a non-retriable error are dead-lettered after 3 attempts (`outbox_event.dead_at`):
  `SELECT id, event_type, attempts, last_error FROM outbox_event WHERE dead_at IS NOT NULL;`
- Broker outages are retriable: the publisher waits and retries without counting attempts.
