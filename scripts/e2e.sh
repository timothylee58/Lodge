#!/usr/bin/env bash
# End-to-end suite: builds and starts the full stack with the e2e overlay, runs Playwright in Docker, tears down.
# Usage: scripts/e2e.sh            (POSTGRES_PORT=55432 scripts/e2e.sh if 5432 is taken)
set -euo pipefail
cd "$(dirname "$0")/.."
compose() { docker compose -p claimdesk-e2e -f docker-compose.yml -f docker-compose.e2e.yml "$@"; }
trap 'compose down -v >/dev/null 2>&1 || true' EXIT
compose up -d --build
for _ in $(seq 1 60); do
  curl -sf localhost:4200/ >/dev/null && curl -sf localhost:8080/actuator/health >/dev/null && curl -sf localhost:8081/actuator/health >/dev/null && break
  sleep 3
done
docker run --rm --ipc=host -e E2E_BASE_URL=http://host.docker.internal:4200 \
  -v "$PWD/frontend":/app -w /app mcr.microsoft.com/playwright:v1.48.2-jammy \
  sh -c 'npm install --no-audit --no-fund >/dev/null 2>&1 && npx playwright test'
