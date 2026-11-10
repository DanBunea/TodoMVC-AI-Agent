import { test, expect } from '@playwright/test';
import { installConsoleGuard } from './helpers/console-guard';
import { openAsFreshUser } from './helpers/app';

// The chat panel's own UI, without sending anything to the LLM.
installConsoleGuard();

test('the chat opens empty, closes, and reopens the same chat', async ({ page, context }) => {
  await openAsFreshUser(page, context);
  const openChat = page.getByTestId('open-chat');
  await expect(openChat).toHaveAttribute('placeholder', 'Ask AI to help with your todos...');

  await openChat.click();
  await expect(page.getByTestId('chat-input')).toBeEnabled();
  await expect(page.getByText('No messages yet. Start a conversation!')).toBeVisible();
  await expect(page.getByTestId('chat-send')).toBeVisible();

  await page.getByTestId('close-chat').click();
  await expect(page.getByTestId('chat-input')).toHaveCount(0);
  await expect(openChat).toHaveAttribute('placeholder', 'Continue your chat...');

  await openChat.click();
  await expect(page.getByTestId('chat-input')).toBeEnabled();
});

test('the chats list shows the chat and Cancel goes back to it', async ({ page, context }) => {
  await openAsFreshUser(page, context);
  await page.getByTestId('open-chat').click();
  await expect(page.getByTestId('chat-input')).toBeVisible();

  await page.getByTitle('List chats').click();
  await expect(page.getByText(/^Chat [0-9a-f]{8}\.\.\.$/)).toHaveCount(1);

  await page.getByRole('button', { name: 'Cancel' }).click();
  await expect(page.getByTestId('chat-input')).toBeVisible();
});
