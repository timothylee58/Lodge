import { expect, test } from '@playwright/test';
import { EVENT_LABEL, STATUS_LABEL, money, problem } from '../src/app/shared/format';

// Pure unit tests for the helpers every screen relies on (no browser needed).
test.describe('format helpers', () => {
  test('every claim status and event type has a human label', () => {
    for (const s of ['SUBMITTED', 'UNDER_REVIEW', 'PENDING_INFO', 'APPROVED', 'SETTLED', 'REJECTED'] as const) {
      expect(STATUS_LABEL[s]).toBeTruthy();
    }
    for (const e of ['CLAIM_SUBMITTED', 'CLAIM_ASSIGNED', 'INFO_REQUESTED', 'INFO_PROVIDED', 'CLAIM_APPROVED',
      'CLAIM_REJECTED', 'CLAIM_SETTLED', 'RESERVE_CHANGED']) {
      expect(EVENT_LABEL[e]).toBeTruthy();
    }
  });

  test('money keeps the claim currency and shows a dash for missing amounts', () => {
    expect(money(8000, 'MYR')).toContain('8,000');
    expect(money(8000, 'MYR')).toMatch(/MYR|RM/);
    expect(money(null, 'MYR')).toBe('—');
  });

  test('problem() turns server errors into messages a user can act on', () => {
    // Same shape as Angular's HttpErrorResponse (status, error body, headers.get).
    const err = (status: number, error: unknown, headers: Record<string, string> = {}) =>
      ({ status, error, headers: { get: (n: string) => headers[n] ?? null } });
    expect(problem(err(409, { title: 'Invalid state transition', detail: 'Cannot transition claim from APPROVED to REJECTED' })))
      .toBe('Cannot transition claim from APPROVED to REJECTED');
    expect(problem(err(429, null, { 'Retry-After': '900' }))).toBe('Too many attempts. Try again in 15 minutes.');
    expect(problem(new Error('boom'))).toBe('Something went wrong.');
    expect(problem(err(0, null))).toContain('Cannot reach the server');
    expect(problem(err(500, { detail: 'NullPointerException at Foo.java:42' }))).toBe('The server had a problem. Please try again.');
  });
});
