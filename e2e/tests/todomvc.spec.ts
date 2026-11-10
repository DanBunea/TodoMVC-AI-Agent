import { test, expect } from '@playwright/test';
import { installConsoleGuard } from './helpers/console-guard';
import { openAsFreshUser, todo, addTodo } from './helpers/app';

installConsoleGuard();

test('Enter adds a todo and clears the input', async ({ page, context }) => {
  await openAsFreshUser(page, context);
  const input = page.getByTestId('new-todo');

  await input.fill('buy milk');
  await input.press('Enter');
  await expect(todo(page, 'buy milk')).toBeVisible();
  await expect(input).toHaveValue('');

  await addTodo(page, 'walk the dog');
  await expect(page.getByTestId('todo-item')).toHaveCount(2);
  await expect(page.getByTestId('remaining')).toHaveText('2 items left');
});

test('toggle, filters and clear completed', async ({ page, context }) => {
  await openAsFreshUser(page, context);
  await addTodo(page, 'buy milk');
  await addTodo(page, 'walk the dog');

  await todo(page, 'buy milk').getByTestId('todo-toggle').click();
  await expect(page.getByTestId('remaining')).toHaveText('1 item left');
  await expect(todo(page, 'buy milk').getByTestId('todo-toggle')).toBeChecked();

  await page.getByTestId('filter-active').click();
  await expect(todo(page, 'buy milk')).toHaveCount(0);
  await expect(todo(page, 'walk the dog')).toBeVisible();

  await page.getByTestId('filter-completed').click();
  await expect(todo(page, 'buy milk')).toBeVisible();
  await expect(todo(page, 'walk the dog')).toHaveCount(0);

  await page.getByTestId('filter-all').click();
  await expect(page.getByTestId('todo-item')).toHaveCount(2);

  await page.getByTestId('clear-completed').click();
  await expect(todo(page, 'buy milk')).toHaveCount(0);
  await expect(page.getByTestId('todo-item')).toHaveCount(1);
  await expect(page.getByTestId('clear-completed')).toHaveCount(0);
});

test('toggle all marks every todo complete, and back', async ({ page, context }) => {
  await openAsFreshUser(page, context);
  await addTodo(page, 'one');
  await addTodo(page, 'two');

  await page.getByTestId('toggle-all').click();
  await expect(page.getByTestId('remaining')).toHaveText('0 items left');

  await page.getByTestId('toggle-all').click();
  await expect(page.getByTestId('remaining')).toHaveText('2 items left');
});

test('double-click edits, Enter saves, Escape cancels', async ({ page, context }) => {
  await openAsFreshUser(page, context);
  await addTodo(page, 'draft');

  await todo(page, 'draft').getByTestId('todo-label').dblclick();
  const edit = page.getByTestId('edit-todo');
  await expect(edit).toHaveValue('draft');
  await edit.fill('final');
  await edit.press('Enter');
  await expect(todo(page, 'final')).toBeVisible();
  await expect(page.getByTestId('edit-todo')).toHaveCount(0);

  await todo(page, 'final').getByTestId('todo-label').dblclick();
  await page.getByTestId('edit-todo').fill('discarded');
  await page.getByTestId('edit-todo').press('Escape');
  await expect(todo(page, 'final')).toBeVisible();
  await expect(todo(page, 'discarded')).toHaveCount(0);
});

test('delete removes a todo', async ({ page, context }) => {
  await openAsFreshUser(page, context);
  await addTodo(page, 'keep');
  await addTodo(page, 'remove me');

  await todo(page, 'remove me').getByTestId('todo-delete').click();
  await expect(todo(page, 'remove me')).toHaveCount(0);
  await expect(todo(page, 'keep')).toBeVisible();
});

test('todos survive a reload and are private to each user', async ({ page, context, browser }) => {
  await openAsFreshUser(page, context);
  await addTodo(page, 'persisted');

  await page.reload();
  await expect(todo(page, 'persisted')).toBeVisible();

  const otherContext = await browser.newContext();
  const other = await otherContext.newPage();
  await openAsFreshUser(other, otherContext);
  await expect(other.getByTestId('todo-item')).toHaveCount(0);
  await otherContext.close();
});
