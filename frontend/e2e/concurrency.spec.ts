import { expect, test } from '@playwright/test';
import { apiStaff, apiSubmitClaim, uiLogin, unique } from './helpers';

test('if another officer picks up a claim first, the UI explains the 409 instead of failing', async ({ page, request }) => {
  const claim = await apiSubmitClaim(request, unique('Race test'));
  await uiLogin(page, 'wei@demo.local');
  await page.goto(`/workbench/${claim.id}`);
  await expect(page.getByRole('button', { name: 'Pick up this claim' })).toBeVisible();

  // Priya wins the race after Wei's page has loaded.
  expect((await apiStaff(request, 'priya@demo.local', claim.id, 'assign')).status()).toBe(200);

  await page.getByRole('button', { name: 'Pick up this claim' }).click();
  await expect(page.getByRole('alert')).toContainText('The claim has been refreshed');
  await expect(page.getByText('Assigned to Priya Officer. Only they can act on it.')).toBeVisible();
});
