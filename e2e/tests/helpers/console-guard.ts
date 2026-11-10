import { test, expect, Page } from '@playwright/test';

/**
 * Fails a test on any browser-side error: uncaught exceptions (`pageerror`)
 * and `console.error` output.
 *
 * Why: a single invalid Datastar expression (a `?` in a signal name, a map
 * bound to an input) throws inside Datastar's MutationObserver and aborts the
 * attribute pass for the whole patched subtree — `data-show`s never apply,
 * spinners never hide — with no server-side symptom. Playwright's `click()`
 * and `toBeVisible()` still pass. Only the console knows.
 *
 * Usage: `installConsoleGuard();` at the top level of a spec file.
 */

const IGNORED = [
  /Failed to load resource/i, // 404s (favicon) are not JS errors
  /favicon/i,
  /net::ERR_/i, // aborted SSE/fetch on navigation
  /ResizeObserver loop/i,
  // page.goto()/reload while a Datastar @post/@get is in flight aborts the
  // fetch; Datastar logs the abort + retry as console.error. Network noise.
  /^Failed to fetch$/,
  /^network error$/i,
  /Datastar failed to reach .* retrying/i,
];

function ignored(text: string): boolean {
  return IGNORED.some((re) => re.test(text));
}

export function collectConsoleErrors(page: Page): () => string[] {
  const errors: string[] = [];
  page.on('pageerror', (err) => errors.push(`pageerror: ${err.message}`));
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    const text = msg.text();
    if (ignored(text)) return;
    errors.push(`console.error: ${text}`);
  });
  return () => errors;
}

export function installConsoleGuard(): void {
  let read: (() => string[]) | undefined;
  test.beforeEach(async ({ page }) => {
    read = collectConsoleErrors(page);
  });
  test.afterEach(async () => {
    const errors = read ? read() : [];
    read = undefined;
    expect(errors, 'browser console must be free of errors').toEqual([]);
  });
}
