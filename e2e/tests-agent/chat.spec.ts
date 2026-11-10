import { test, expect, Page } from '@playwright/test';
import { installConsoleGuard } from '../tests/helpers/console-guard';
import { openAsFreshUser, todo, addTodo } from '../tests/helpers/app';

// Real OpenAI calls. Assert on outcomes, never on the model's wording.
installConsoleGuard();

async function ask(page: Page, message: string): Promise<void> {
  const input = page.getByTestId('chat-input');
  await expect(input).toBeEnabled();
  const before = await page.getByTestId('message-assistant').count();
  await input.fill(message);
  await input.press('Enter');
  await expect(page.getByTestId('message-user').last()).toContainText(message);
  // The turn is over when an assistant reply is on screen and the input is
  // usable again (completion_done moved the chat back to `displayed`).
  await expect.poll(() => page.getByTestId('message-assistant').count()).toBeGreaterThan(before);
  await expect(page.getByTestId('chat-stop')).toHaveCount(0);
  await expect(page.getByTestId('chat-input')).toBeEnabled();
}

test('asking to add a todo adds it and the turn visibly finishes', async ({ page, context }) => {
  await openAsFreshUser(page, context);
  await page.getByTestId('open-chat').click();

  await ask(page, 'add a new todo "buy oat milk"');
  await expect(page.getByTestId('tool-call').first()).toBeVisible();

  await page.getByTestId('close-chat').click();
  await expect(todo(page, 'buy oat milk')).toBeVisible();
});

test('asking to mark a todo done completes it', async ({ page, context }) => {
  await openAsFreshUser(page, context);
  await addTodo(page, 'water the plants');
  await expect(page.getByTestId('remaining')).toHaveText('1 item left');

  await page.getByTestId('open-chat').click();
  await ask(page, 'mark "water the plants" as done');

  await page.getByTestId('close-chat').click();
  await expect(page.getByTestId('remaining')).toHaveText('0 items left');
  await expect(todo(page, 'water the plants').getByTestId('todo-toggle')).toBeChecked();
});

test('a follow-up message in the same chat works', async ({ page, context }) => {
  await openAsFreshUser(page, context);
  await page.getByTestId('open-chat').click();

  await ask(page, 'add a todo "first"');
  await ask(page, 'add a todo "second"');

  await page.getByTestId('close-chat').click();
  await expect(todo(page, 'first')).toBeVisible();
  await expect(todo(page, 'second')).toBeVisible();
});
