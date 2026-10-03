import { defineConfig } from '@playwright/test';

// Prove dell'harness delle journey contro uno stack simulato (ADR-053, Q-693): nessun browser, nessuno stack, nessun
// login. `pnpm test:mock`. Le journey vere stanno in `tests/` e usano playwright.config.ts.
export default defineConfig({
  testDir: './journeys',
  testMatch: /.*\.mock\.spec\.ts/,
  outputDir: './test-results-mock',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  timeout: 120_000,
  reporter: [['list']],
});
