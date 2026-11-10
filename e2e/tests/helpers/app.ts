import { expect, BrowserContext, Page, Locator } from '@playwright/test';
import { randomUUID } from 'node:crypto';

/**
 * Opens the app as a brand-new user. The server's stand-in auth reads the
 * `dev-user` cookie, and todos and chats are stored per user, so every test
 * starts from an empty list without restarting the server.
 */
export async function openAsFreshUser(page: Page, context: BrowserContext): Promise<string> {
  const user = `e2e-${randomUUID()}`;
  const url = new URL(process.env.E2E_BASE_URL || 'http://localhost:7799');
  await context.addCookies([{ name: 'dev-user', value: user, domain: url.hostname, path: '/' }]);
  await page.goto('/');
  await expect(page.getByTestId('new-todo')).toBeVisible();
  return user;
}

export function todo(page: Page, text: string): Locator {
  return page.getByTestId('todo-item').filter({ hasText: text });
}

export async function addTodo(page: Page, text: string): Promise<void> {
  const input = page.getByTestId('new-todo');
  await input.fill(text);
  await input.press('Enter');
  await expect(todo(page, text)).toBeVisible();
}
