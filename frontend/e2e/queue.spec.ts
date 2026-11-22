import { expect, test } from '@playwright/test';
import { apiSubmitClaim, uiLogin, unique } from './helpers';

test('officer queue shows claim age, SLA state and an overdue summary, and can sort by longest waiting', async ({ page, request }) => {
  const claim = await apiSubmitClaim(request, unique('SLA view'));
  await uiLogin(page, 'wei@demo.local');
  await expect(page.getByText(/My open claims:/)).toBeVisible();
  await expect(page.getByText(/Waiting for an officer:/)).toBeVisible();

  // Material renders toggles in a single-selection group as role=radio, not button.
  await page.getByRole('radio', { name: 'Longest waiting' }).click();
  await expect(page.getByText('Longest in current status first.')).toBeVisible();

  const row = page.getByRole('row').filter({ hasText: claim.number });
  await expect(row).toBeVisible();
  await expect(row.getByText('On track')).toBeVisible();
  await expect(row.getByText(/in status \d+m/)).toBeVisible();
});
