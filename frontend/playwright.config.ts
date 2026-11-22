import { defineConfig } from '@playwright/test';

/** Runs against the real compose stack (UI + both services + Postgres + Kafka). See scripts/e2e.sh. */
export default defineConfig({
  testDir: './e2e',
  workers: 1,              // shared demo users and a shared read model: keep it serial and deterministic
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list']],
  use: {
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:4200',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
