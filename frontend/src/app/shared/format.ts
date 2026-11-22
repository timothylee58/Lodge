import type { ClaimStatus } from '../core/models';

/** The parts of Angular's HttpErrorResponse we use. Structural, so this file has no runtime Angular dependency. */
export interface HttpErrorLike { status: number; error: unknown; headers?: { get(name: string): string | null } }

function isHttpError(e: unknown): e is HttpErrorLike {
  return typeof e === 'object' && e !== null && typeof (e as HttpErrorLike).status === 'number';
}

export const STATUS_LABEL: Record<ClaimStatus, string> = {
  SUBMITTED: 'Submitted', UNDER_REVIEW: 'Under review', PENDING_INFO: 'Waiting for information',
  APPROVED: 'Approved', SETTLED: 'Settled', REJECTED: 'Rejected',
};

export const EVENT_LABEL: Record<string, string> = {
  CLAIM_SUBMITTED: 'Claim submitted', CLAIM_ASSIGNED: 'Picked up by a claims officer',
  INFO_REQUESTED: 'More information requested', INFO_PROVIDED: 'Information provided',
  CLAIM_APPROVED: 'Claim approved', CLAIM_REJECTED: 'Claim rejected', CLAIM_SETTLED: 'Claim settled',
  RESERVE_CHANGED: 'Reserve changed',
};

export function money(amount: number | null | undefined, currency: string): string {
  if (amount === null || amount === undefined) return '—';
  return new Intl.NumberFormat(undefined, { style: 'currency', currency, maximumFractionDigits: 2 }).format(amount);
}

export function when(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

/** Human message from an RFC 7807 ProblemDetail (or anything else the server sent). */
export function problem(err: unknown): string {
  if (!isHttpError(err)) return 'Something went wrong.';
  if (err.status === 0) return 'Cannot reach the server. Check your connection and try again.';
  if (err.status === 429) {
    const s = Number(err.headers?.get('Retry-After'));
    return s ? `Too many attempts. Try again in ${Math.ceil(s / 60)} minute${s > 60 ? 's' : ''}.` : 'Too many attempts. Try again later.';
  }
  const body = err.error as { detail?: string; title?: string } | null;
  if (err.status === 400 && body?.detail === 'Invalid request content.') return 'Please check the highlighted fields.';
  if (err.status >= 500) return 'The server had a problem. Please try again.';
  return body?.detail || body?.title || `Request failed (${err.status}).`;
}

export function todayIso(): string {
  const d = new Date();
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}
