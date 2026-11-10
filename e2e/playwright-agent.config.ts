import { defineConfig } from '@playwright/test';

// Agent suite: real OpenAI calls (needs OPENAI_API_KEY). `bb agent-e2e-test`
// starts the app on 7799. The model is non-deterministic, so the specs assert
// on outcomes (the todo list, the turn finishing), never on its wording.
export default defineConfig({
  testDir: './tests-agent',
  timeout: 120_000,
  expect: { timeout: 60_000 },
  retries: 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:7799',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
});
