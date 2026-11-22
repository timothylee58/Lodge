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

  // exact link match: a substring filter would also match CLM-10010 for CLM-1001 (review nit)
  const row = page.getByRole('row').filter({ has: page.getByRole('link', { name: claim.number, exact: true }) });
  await expect(row).toBeVisible();
  await expect(row.getByText('On track')).toBeVisible();
  await expect(row.getByText(/in status \d+m/)).toBeVisible();
});
