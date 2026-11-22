// Mirrors the backend DTOs (claims-service + reporting-service). Next step: generate from the OpenAPI spec.
export type Role = 'CLAIMANT' | 'OFFICER' | 'MANAGER';
export type SlaState = 'ON_TRACK' | 'DUE_SOON' | 'BREACHED' | 'NONE';
/** breached = staff delays; chaseClaimant = past the claimant's response target (not the officer's delay). */
export interface SlaCounts { breached: number; dueSoon: number; onTrack: number; chaseClaimant: number; }
export interface QueueSummary { mine: SlaCounts; unassigned: SlaCounts; }
export type ClaimStatus = 'SUBMITTED' | 'UNDER_REVIEW' | 'PENDING_INFO' | 'APPROVED' | 'SETTLED' | 'REJECTED';

export interface Session { token: string; userId: string; name: string; role: Role; expiresAt: number; }

export interface Policy {
  id: string; policyNumber: string; type: string; market: string; coverageLimit: number; currency: string;
}

export interface ClaimSummary {
  id: string; claimNumber: string; status: ClaimStatus; type: string; market: string; incidentDate: string;
  description: string; reserveAmount: number | null; settledAmount: number | null; currency: string;
  assigneeId: string | null; assigneeName: string | null; createdAt: string; updatedAt: string; version: number;
  /** Staff only (null for claimants). */
  statusChangedAt: string | null; sla: SlaState | null; slaDueAt: string | null;
}

export interface TimelineEntry { type: string; at: string; actorId: string; detail: Record<string, unknown>; }
export interface InfoRequestView {
  id: string; question: string; response: string | null; status: 'OPEN' | 'ANSWERED'; createdAt: string; answeredAt: string | null;
}
export interface ClaimDetail { claim: ClaimSummary; timeline: TimelineEntry[]; infoRequests: InfoRequestView[]; }
export interface Page<T> { content: T[]; page: number; size: number; totalElements: number; }

export interface ExposureReport {
  asOf: string | null; generatedAt: string;
  rows: { market: string; currency: string; type: string; openClaims: number; exposure: number }[];
  totalsByCurrency: { currency: string; openClaims: number; exposure: number }[];
}
export interface StatusReport { asOf: string | null; generatedAt: string; rows: { market: string; status: ClaimStatus; claims: number }[]; }
export interface WorkloadReport {
  asOf: string | null; generatedAt: string; unassignedClaims: number;
  officers: { assigneeId: string; assigneeName: string; openClaims: number; underReview: number; pendingInfo: number; approved: number }[];
}
