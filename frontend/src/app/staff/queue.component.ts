import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { ClaimStatus, ClaimSummary, Page } from '../core/models';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { STATUS_LABEL, money, problem, when } from '../shared/format';

type Tab = 'unassigned' | 'me' | 'all';

@Component({
  selector: 'cd-queue',
  standalone: true,
  imports: [FormsModule, RouterLink, StatusBadgeComponent],
  template: `
    <div class="page-head">
      <h1>Claims queue</h1>
      <div class="tabs" role="tablist">
        <button role="tab" [attr.aria-selected]="tab() === 'unassigned'" (click)="select('unassigned')">Unassigned</button>
        @if (isOfficer()) { <button role="tab" [attr.aria-selected]="tab() === 'me'" (click)="select('me')">Assigned to me</button> }
        <button role="tab" [attr.aria-selected]="tab() === 'all'" (click)="select('all')">All</button>
      </div>
    </div>
    @if (tab() !== 'unassigned') {
      <label class="inline">Status
        <select [(ngModel)]="status" (ngModelChange)="load(0)">
          <option value="">Any</option>
          @for (s of statuses; track s) { <option [value]="s">{{ label[s] }}</option> }
        </select>
      </label>
    }
    @if (notice()) { <p class="notice" role="status">{{ notice() }}</p> }
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (page(); as p) {
      <div class="table-wrap">
        <table>
          <thead><tr><th>Claim</th><th>Market</th><th>Type</th><th>Incident</th><th>Status</th><th>Officer</th><th class="num">Reserve</th><th></th></tr></thead>
          <tbody>
            @for (c of p.content; track c.id) {
              <tr>
                <td><a [routerLink]="['/workbench', c.id]">{{ c.claimNumber }}</a><div class="muted small">{{ when(c.createdAt) }}</div></td>
                <td>{{ c.market }}</td><td>{{ c.type }}</td><td>{{ c.incidentDate }}</td>
                <td><cd-status [status]="c.status" /></td>
                <td>{{ c.assigneeName ?? '—' }}</td>
                <td class="num">{{ money(c.reserveAmount, c.currency) }}</td>
                <td class="num">
                  @if (isOfficer() && c.status === 'SUBMITTED') {
                    <button class="primary small" [disabled]="busyId() === c.id" (click)="pickUp(c)">Pick up</button>
                  }
                </td>
              </tr>
            } @empty {
              <tr><td colspan="8" class="muted center">{{ tab() === 'unassigned' ? 'Nothing waiting. The queue is clear.' : 'No claims match.' }}</td></tr>
            }
          </tbody>
        </table>
      </div>
      <div class="pager">
        <span class="muted">{{ p.totalElements }} claim{{ p.totalElements === 1 ? '' : 's' }} · oldest first</span>
        <button [disabled]="p.page === 0" (click)="load(p.page - 1)">Previous</button>
        <button [disabled]="(p.page + 1) * p.size >= p.totalElements" (click)="load(p.page + 1)">Next</button>
      </div>
    } @else if (!error()) { <p class="muted">Loading…</p> }`,
})
export class QueueComponent {
  private api = inject(ApiService);
  private auth = inject(AuthService);
  private router = inject(Router);
  isOfficer = computed(() => this.auth.role() === 'OFFICER');
  tab = signal<Tab>(this.isOfficer() ? 'unassigned' : 'all');
  page = signal<Page<ClaimSummary> | null>(null);
  error = signal<string | null>(null);
  notice = signal<string | null>(null);
  busyId = signal<string | null>(null);
  status: ClaimStatus | '' = '';
  statuses = Object.keys(STATUS_LABEL) as ClaimStatus[];
  label = STATUS_LABEL;
  money = money; when = when;

  constructor() { this.load(0); }

  select(t: Tab) { this.tab.set(t); this.status = ''; this.load(0); }

  load(pageNo: number) {
    const t = this.tab();
    this.error.set(null);
    this.api.queue({
      status: t === 'unassigned' ? 'SUBMITTED' : this.status,
      assignee: t === 'unassigned' ? 'unassigned' : t === 'me' ? 'me' : undefined,
      page: pageNo, size: 20,
    }).subscribe({ next: p => this.page.set(p), error: e => this.error.set(problem(e)) });
  }

  pickUp(c: ClaimSummary) {
    this.busyId.set(c.id);
    this.notice.set(null);
    this.api.assign(c.id).subscribe({
      next: () => this.router.navigate(['/workbench', c.id]),
      error: e => {
        this.busyId.set(null);
        // 409 = someone else got there first (optimistic lock / state machine). Refresh, don't alarm.
        if (e.status === 409) { this.notice.set(`${c.claimNumber} was just picked up by someone else.`); this.load(this.page()?.page ?? 0); }
        else this.error.set(problem(e));
      },
    });
  }
}
