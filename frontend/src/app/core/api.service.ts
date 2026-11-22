import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { ClaimDetail, ClaimStatus, ClaimSummary, ExposureReport, Page, Policy, QueueSummary, StatusReport, WorkloadReport } from './models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);

  // claimant
  myPolicies() { return this.http.get<Policy[]>('/api/policies/mine'); }
  myClaims() { return this.http.get<ClaimSummary[]>('/api/claims', { params: { mine: true } }); }
  claim(id: string) { return this.http.get<ClaimDetail>(`/api/claims/${id}`); }
  submit(body: { policyId: string; incidentDate: string; description: string }) {
    return this.http.post<ClaimDetail>('/api/claims', body);
  }
  respond(id: string, response: string) { return this.http.post<ClaimDetail>(`/api/claims/${id}/info-responses`, { response }); }

  // staff
  queue(opts: { status?: ClaimStatus | ''; assignee?: string; page: number; size: number; sort?: 'oldest' | 'waiting' }) {
    let params = new HttpParams().set('page', opts.page).set('size', opts.size).set('sort', opts.sort ?? 'oldest');
    if (opts.status) params = params.set('status', opts.status);
    if (opts.assignee) params = params.set('assignee', opts.assignee);
    return this.http.get<Page<ClaimSummary>>('/api/staff/claims', { params });
  }
  queueSummary() { return this.http.get<QueueSummary>('/api/staff/claims/summary'); }
  private staff(id: string, action: string, body: unknown = {}) {
    return this.http.post<ClaimDetail>(`/api/staff/claims/${id}/${action}`, body);
  }
  assign(id: string) { return this.staff(id, 'assign'); }
  requestInfo(id: string, question: string) { return this.staff(id, 'info-requests', { question }); }
  approve(id: string, reserveAmount: number) { return this.staff(id, 'approve', { reserveAmount }); }
  reject(id: string, reason: string) { return this.staff(id, 'reject', { reason }); }
  settle(id: string, amount: number) { return this.staff(id, 'settle', { amount }); }
  adjustReserve(id: string, reserveAmount: number) {
    return this.http.patch<ClaimDetail>(`/api/staff/claims/${id}/reserve`, { reserveAmount });
  }

  // manager (reporting-service, eventually consistent)
  exposure() { return this.http.get<ExposureReport>('/api/reports/exposure'); }
  byStatus() { return this.http.get<StatusReport>('/api/reports/claims-by-status'); }
  workload() { return this.http.get<WorkloadReport>('/api/reports/workload'); }
}
