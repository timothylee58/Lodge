import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { ClaimStatus, ClaimSummary, Page, QueueSummary } from '../core/models';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { SlaChipComponent } from '../shared/sla-chip.component';
import { STATUS_LABEL, age, money, problem, when } from '../shared/format';
import { MATERIAL } from '../shared/material';

type Tab = 'unassigned' | 'me' | 'all';

@Component({
  selector: 'lg-queue',
  standalone: true,
  imports: [FormsModule, RouterLink, StatusBadgeComponent, SlaChipComponent, ...MATERIAL],
  template: `
    <div class="flex flex-wrap items-center justify-between gap-4">
      <h1 class="text-2xl">Claims queue</h1>
      <mat-button-toggle-group [value]="tab()" (change)="select($event.value)" aria-label="Queue view">
        <mat-button-toggle value="unassigned">Unassigned</mat-button-toggle>
        @if (isOfficer()) { <mat-button-toggle value="me">Assigned to me</mat-button-toggle> }
        <mat-button-toggle value="all">All</mat-button-toggle>
      </mat-button-toggle-group>
    </div>
    @if (summary(); as sm) {
      <div class="flex flex-wrap gap-3 text-sm" aria-live="polite">
        @if (isOfficer()) {
          <span class="rounded-lg border border-slate-300 px-3 py-1.5 dark:border-slate-700">My open claims:
            <strong [class.text-red-700]="sm.mine.breached > 0" [class.dark:text-red-300]="sm.mine.breached > 0">{{ sm.mine.breached }} overdue</strong> ·
            {{ sm.mine.dueSoon }} due soon · {{ sm.mine.onTrack }} on track</span>
        }
        <span class="rounded-lg border border-slate-300 px-3 py-1.5 dark:border-slate-700">Waiting for an officer:
          <strong [class.text-red-700]="sm.unassigned.breached > 0" [class.dark:text-red-300]="sm.unassigned.breached > 0">{{ sm.unassigned.breached }} overdue</strong> ·
          {{ sm.unassigned.dueSoon }} due soon · {{ sm.unassigned.onTrack }} on track</span>
      </div>
    }
    <div class="flex flex-wrap items-center gap-4">
      <mat-button-toggle-group [value]="sort()" (change)="sortBy($event.value)" aria-label="Sort queue" hideSingleSelectionIndicator>
        <mat-button-toggle value="oldest">Oldest first</mat-button-toggle>
        <mat-button-toggle value="waiting">Longest waiting</mat-button-toggle>
      </mat-button-toggle-group>
    @if (tab() !== 'unassigned') {
      <mat-form-field appearance="outline" class="!w-64" subscriptSizing="dynamic"><mat-label>Status</mat-label>
        <mat-select [(ngModel)]="status" (ngModelChange)="load(0)">
          <mat-option value="">Any</mat-option>
          @for (s of statuses; track s) { <mat-option [value]="s">{{ label[s] }}</mat-option> }
        </mat-select>
      </mat-form-field>
    }
    </div>
    @if (notice()) { <p class="m-0 rounded-lg border border-amber-400 bg-amber-50 px-3 py-2 dark:bg-amber-950/40" role="status">{{ notice() }}</p> }
    @if (error()) { <p class="text-red-700 dark:text-red-300" role="alert">{{ error() }}</p> }
    @if (page(); as p) {
      <div class="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-800">
        <table mat-table [dataSource]="p.content" class="w-full">
          <ng-container matColumnDef="claim"><th mat-header-cell *matHeaderCellDef>Claim</th>
            <td mat-cell *matCellDef="let c"><a [routerLink]="['/workbench', c.id]">{{ c.claimNumber }}</a><div class="text-xs text-slate-500">{{ when(c.createdAt) }}</div></td></ng-container>
          <ng-container matColumnDef="market"><th mat-header-cell *matHeaderCellDef>Market</th><td mat-cell *matCellDef="let c">{{ c.market }}</td></ng-container>
          <ng-container matColumnDef="type"><th mat-header-cell *matHeaderCellDef>Type</th><td mat-cell *matCellDef="let c">{{ c.type }}</td></ng-container>
          <ng-container matColumnDef="incident"><th mat-header-cell *matHeaderCellDef>Incident</th><td mat-cell *matCellDef="let c">{{ c.incidentDate }}</td></ng-container>
          <ng-container matColumnDef="status"><th mat-header-cell *matHeaderCellDef>Status</th><td mat-cell *matCellDef="let c"><lg-status [status]="c.status" /></td></ng-container>
          <ng-container matColumnDef="age"><th mat-header-cell *matHeaderCellDef>Age</th>
            <td mat-cell *matCellDef="let c" class="tabular-nums">{{ age(c.createdAt) }}
              <div class="text-xs text-slate-500">in status {{ age(c.statusChangedAt) }}</div></td></ng-container>
          <ng-container matColumnDef="sla"><th mat-header-cell *matHeaderCellDef>SLA</th>
            <td mat-cell *matCellDef="let c"><lg-sla [sla]="c.sla" [status]="c.status" [dueAt]="c.slaDueAt" /></td></ng-container>
          <ng-container matColumnDef="officer"><th mat-header-cell *matHeaderCellDef>Officer</th><td mat-cell *matCellDef="let c">{{ c.assigneeName ?? '—' }}</td></ng-container>
          <ng-container matColumnDef="reserve"><th mat-header-cell *matHeaderCellDef class="!text-right">Reserve</th>
            <td mat-cell *matCellDef="let c" class="!text-right tabular-nums">{{ money(c.reserveAmount, c.currency) }}</td></ng-container>
          <ng-container matColumnDef="act"><th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let c" class="!text-right">
              @if (isOfficer() && c.status === 'SUBMITTED') {
                <button mat-flat-button [disabled]="busyId() === c.id" (click)="pickUp(c)">Pick up</button>
              }
            </td></ng-container>
          <tr mat-header-row *matHeaderRowDef="cols"></tr>
          <tr mat-row *matRowDef="let row; columns: cols"></tr>
          <tr class="mat-mdc-row" *matNoDataRow><td class="p-6 text-center text-slate-500" [attr.colspan]="cols.length">
            {{ tab() === 'unassigned' ? 'Nothing waiting. The queue is clear.' : 'No claims match.' }}</td></tr>
        </table>
        <mat-paginator [length]="p.totalElements" [pageIndex]="p.page" [pageSize]="p.size" [hidePageSize]="true"
                       (page)="load($event.pageIndex)" aria-label="Queue pages" />
      </div>
      <p class="m-0 text-sm text-slate-500">{{ sort() === 'waiting' ? 'Longest in current status first.' : 'Oldest first.' }} SLA targets: pick up 24h, decide 5d, claimant answers 7d, settle 3d.</p>
    } @else if (!error()) { <mat-progress-bar mode="indeterminate" /> }`,
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
  cols = ['claim', 'market', 'type', 'status', 'age', 'sla', 'officer', 'reserve', 'act'];
  sort = signal<'oldest' | 'waiting'>('oldest');
  summary = signal<QueueSummary | null>(null);
  statuses = Object.keys(STATUS_LABEL) as ClaimStatus[];
  label = STATUS_LABEL;
  money = money; when = when; age = age;

  constructor() { this.load(0); }

  sortBy(s: 'oldest' | 'waiting') { this.sort.set(s); this.load(0); }

  select(t: Tab) { this.tab.set(t); this.status = ''; this.load(0); }

  load(pageNo: number) {
    const t = this.tab();
    this.error.set(null);
    this.api.queue({
      status: t === 'unassigned' ? 'SUBMITTED' : this.status,
      assignee: t === 'unassigned' ? 'unassigned' : t === 'me' ? 'me' : undefined,
      page: pageNo, size: 20, sort: this.sort(),
    }).subscribe({ next: p => this.page.set(p), error: e => this.error.set(problem(e)) });
    this.api.queueSummary().subscribe({ next: s => this.summary.set(s), error: () => this.summary.set(null) });
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
