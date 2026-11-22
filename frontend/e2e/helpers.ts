import { APIRequestContext, Page, expect } from '@playwright/test';

export const MOTOR_POLICY = '10000000-0000-0000-0000-000000000001';

export async function uiLogin(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).not.toHaveURL(/\/login$/);
}

export async function apiToken(request: APIRequestContext, email: string): Promise<string> {
  const r = await request.post('/api/auth/login', { data: { email, password: 'password' } });
  expect(r.status(), `login ${email}`).toBe(200);
  return (await r.json()).token;
}

/** Claims submitted through the API so a test controls its own data; returns the claim id. */
export async function apiSubmitClaim(request: APIRequestContext, description: string): Promise<{ id: string; number: string }> {
  const token = await apiToken(request, 'aina@demo.local');
  const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);
  const r = await request.post('/api/claims', {
    headers: { Authorization: `Bearer ${token}` },
    data: { policyId: MOTOR_POLICY, incidentDate: yesterday, description },
  });
  expect(r.status()).toBe(201);
  const body = await r.json();
  return { id: body.claim.id, number: body.claim.claimNumber };
}

export async function apiStaff(request: APIRequestContext, email: string, claimId: string, action: string, data: object = {}) {
  const token = await apiToken(request, email);
  return request.post(`/api/staff/claims/${claimId}/${action}`, { headers: { Authorization: `Bearer ${token}` }, data });
}

/** Fails the test on any CSP violation or uncaught page error. */
export function guardConsole(page: Page): string[] {
  const problems: string[] = [];
  page.on('pageerror', e => problems.push(`pageerror: ${e.message}`));
  page.on('console', m => { if (m.type() === 'error' && /Content Security Policy|Refused to/.test(m.text())) problems.push(m.text()); });
  return problems;
}

export const unique = (label: string) => `${label} ${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
