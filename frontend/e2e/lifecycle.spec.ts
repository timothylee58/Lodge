import { expect, test } from '@playwright/test';
import { apiStaff, apiSubmitClaim, guardConsole, uiLogin, unique } from './helpers';

test('full lifecycle across roles: request info -> claimant answers -> approve -> settle -> dashboard', async ({ browser, request }) => {
  const claim = await apiSubmitClaim(request, unique('Hail damage to the roof'));
  const officer = await browser.newPage();
  const claimant = await browser.newPage();
  const problems = [...guardConsole(officer)];

  // Officer picks up and asks a question.
  await uiLogin(officer, 'wei@demo.local');
  await officer.goto(`/workbench/${claim.id}`);
  await officer.getByRole('button', { name: 'Pick up this claim' }).click();
  await expect(officer.getByText('Under review')).toBeVisible();
  await officer.getByRole('button', { name: 'Request information' }).click();
  await officer.getByLabel('Question for the claimant').fill('Police report number?');
  await officer.getByRole('button', { name: 'Send request' }).click();
  await expect(officer.getByText('Waiting for the claimant to answer.')).toBeVisible();

  // Claimant sees the request and answers it.
  await uiLogin(claimant, 'aina@demo.local');
  await claimant.goto(`/claims/${claim.id}`);
  await expect(claimant.getByText('We need more information')).toBeVisible();
  await expect(claimant.getByText('Police report number?').first()).toBeVisible();
  await claimant.getByLabel('Your answer').fill('PDRM/2026/1234');
  await claimant.getByRole('button', { name: 'Send answer' }).click();
  await expect(claimant.getByText('We need more information')).toBeHidden();

  // Officer sees the answer, approves with a reserve, then settles.
  await officer.reload();
  await expect(officer.getByText('PDRM/2026/1234').first()).toBeVisible();
  await officer.getByRole('button', { name: 'Approve' }).click();
  await officer.getByLabel(/Reserve/).fill('8000');
  await officer.locator('form button[type=submit]').click();
  await expect(officer.getByText('Reserve set to')).toBeVisible();

  await officer.getByRole('button', { name: 'Settle' }).click();
  await officer.getByLabel(/Settlement amount/).fill('60000');
  await officer.locator('form button[type=submit]').click();
  await expect(officer.getByRole('alert')).toContainText('coverage limit'); // backend rule surfaced in the UI
  await officer.getByLabel(/Settlement amount/).fill('7500');
  await officer.locator('form button[type=submit]').click();
  await expect(officer.getByText('This claim is closed.')).toBeVisible();

  // Claimant sees the payout, and no reserve figure anywhere.
  await claimant.reload();
  await expect(claimant.getByText('We settled this claim for')).toBeVisible();
  await expect(claimant.getByText('Reserve set to')).toHaveCount(0);
  expect(problems).toEqual([]);
});

test('manager dashboard reflects an approval via Kafka (eventually consistent)', async ({ page, request }) => {
  const claim = await apiSubmitClaim(request, unique('Side mirror clipped'));
  expect((await apiStaff(request, 'wei@demo.local', claim.id, 'assign')).status()).toBe(200);
  expect((await apiStaff(request, 'wei@demo.local', claim.id, 'approve', { reserveAmount: 1234 })).status()).toBe(200);

  await uiLogin(page, 'mark@demo.local');
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByText(/Data as of/)).toBeVisible();
  // The read model lags by design; reload until the projection has caught up.
  await expect(async () => {
    await page.reload();
    await expect(page.getByText('Open exposure · MYR')).toBeVisible({ timeout: 2_000 });
    await expect(page.getByRole('cell', { name: 'Wei Officer' })).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
});
