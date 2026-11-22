import { expect, test } from '@playwright/test';

test('wrong password shows a generic message; repeated failures lock with a friendly 429 message', async ({ page }) => {
  const email = `nobody-${Date.now()}@demo.local`; // unknown email: throttled exactly like a real one
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('wrong');
  for (let i = 0; i < 5; i++) {
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('alert')).toHaveText('Email or password is incorrect.');
  }
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('alert')).toHaveText(/Too many attempts\. Try again in 15 minutes\./);
});

test('signing out clears the session and protected pages send you to sign in', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Email').fill('wei@demo.local');
  await page.getByLabel('Password').fill('password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/queue$/);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto('/queue');
  await expect(page).toHaveURL(/\/login$/);
});
