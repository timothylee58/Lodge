import { expect, test } from '@playwright/test';
import { guardConsole, uiLogin, unique } from './helpers';

test('claimant reports an incident and sees it submitted with a timeline', async ({ page }) => {
  const problems = guardConsole(page);
  const description = unique('Rear-ended at a junction');
  await uiLogin(page, 'aina@demo.local');
  await expect(page).toHaveURL(/\/claims$/);

  await page.getByRole('link', { name: 'Report an incident' }).first().click();
  await page.getByLabel('What happened?').fill(description);
  await page.getByRole('button', { name: 'Submit claim' }).click();

  await expect(page).toHaveURL(/\/claims\/[0-9a-f-]{36}$/);
  await expect(page.getByText('Claim submitted')).toBeVisible();
  await expect(page.getByText(description)).toBeVisible();
  await expect(page.getByText('Waiting for a claims officer')).toBeVisible();

  await page.getByRole('link', { name: '← My claims' }).click();
  await expect(page.getByText(description)).toBeVisible();
  expect(problems).toEqual([]);
});

test('a claimant cannot open staff or manager screens (UI guard; the API enforces it too)', async ({ page }) => {
  await uiLogin(page, 'aina@demo.local');
  for (const path of ['/dashboard', '/queue']) {
    await page.goto(path);
    await expect(page).toHaveURL(/\/claims$/);
  }
});
