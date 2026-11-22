# ClaimDesk

Claims intake and handling: claimants report incidents and track them, officers work a queue, managers see exposure.
Backend-first; see `docs/adr/0001-modular-monolith-plus-reporting.md` and `docs/AI_JOURNAL.md`.

## Run locally

**Everything in containers (one command):**

```bash
docker compose up --build                 # Postgres, Kafka, claims-service :8080, reporting-service :8081
# if port 5432 is taken on your machine:  POSTGRES_PORT=55432 docker compose up --build
```

**From your IDE** (fast iteration): start only the infra, then run each service with the `dev` profile:

```bash
docker compose up -d postgres kafka
cd claims-service
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run   # or run from your IDE with that env var
open http://localhost:8080/swagger-ui.html
```

In a second terminal, the read side (Kafka consumer + reports on port 8081):

```bash
cd reporting-service
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

Reports (manager token from `mark@demo.local`): `GET :8081/api/reports/exposure`, `/workload`, `/claims-by-status`.
Each response has `asOf` (freshness of the data) and `generatedAt`. reporting-service keeps its tables in the
`reporting` schema of the same Postgres and never reads claims tables.

`JWT_SECRET` (>= 32 bytes, identical for both services) is **required**; the app refuses to start without it. The `dev` profile supplies a
throwaway local value (`application-dev.yml`). Anywhere else, set `JWT_SECRET` in the environment.

Demo users (password `password`): `aina@demo.local` (claimant), `wei@demo.local` / `priya@demo.local` (officers),
`mark@demo.local` (manager). `POST /api/auth/login` returns a bearer token.

Kafka has two listeners (`kafka:19092` for containers, `localhost:9092` for your machine). Postgres defaults to host
port 5432; set `POSTGRES_PORT` to move it (then use that port in `spring.datasource.url` for IDE runs).
The compose services run the `dev` profile (public throwaway JWT secret + demo users), so every port is bound to
`127.0.0.1` only — nothing is reachable from your network.

**Demo data is dev-only.** Demo users and policies live in `db/demo-seed` and load only when that Flyway location is
enabled (the `dev` profile and the tests). A default (non-dev) start creates no accounts at all. If you have a database
from before this change, reset it once with `docker compose down -v` (the old `V2__seed` migration no longer exists).

**Behind a reverse proxy** (e.g. the frontend's nginx), set on claims-service `SERVER_FORWARD_HEADERS_STRATEGY=native`
and `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES=<regex of your proxy IPs>`, and have the proxy append the client address
(`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`). Otherwise every user shares one login rate-limit
bucket. Leave it off without a proxy: `X-Forwarded-For` is client-controlled.

## Tests

```bash
(cd claims-service && ./mvnw test) && (cd reporting-service && ./mvnw test)
```

Integration tests use Testcontainers (real Postgres + Kafka) and need Docker. With Docker 29+, Testcontainers
>= 1.21.4 is required (pinned in the pom).

## Operations notes

- Dead letters (re-drive/discard API + metrics), alert rules, unlocking users, read-model rebuild: `docs/RUNBOOK.md`.
- Login: 10 requests/min per IP and a 15-minute lockout after 5 consecutive failures per email (429 + `Retry-After`).
  Token signing and the OIDC path: `docs/adr/0002-token-signing-and-oidc-path.md`.
- Broker outages are retriable: the publisher waits and retries without counting attempts.
- Poison messages on the consumer side land in `claims.events.DLT`. See `docs/ARCHITECTURE.md` for guarantees and gaps.
