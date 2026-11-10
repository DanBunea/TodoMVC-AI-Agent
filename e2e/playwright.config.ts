import { defineConfig } from '@playwright/test';

// UI suite: no LLM calls. `bb e2e-test` starts the app on 7799 and sets E2E_BASE_URL.
// Against an already running `bb dev`: E2E_BASE_URL=http://localhost:7777 npx playwright test
export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  retries: 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:7799',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
});
