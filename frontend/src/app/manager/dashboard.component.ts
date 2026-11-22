import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, catchError, forkJoin, switchMap, timer } from 'rxjs';
import { ApiService } from '../core/api.service';
import { ClaimStatus, ExposureReport, StatusReport, WorkloadReport } from '../core/models';
import { STATUS_LABEL, money, problem, when } from '../shared/format';
import { MATERIAL } from '../shared/material';

const ORDER: ClaimStatus[] = ['SUBMITTED', 'UNDER_REVIEW', 'PENDING_INFO', 'APPROVED', 'SETTLED', 'REJECTED'];

@Component({
  selector: 'cd-dashboard',
  standalone: true,
  imports: [...MATERIAL],
  template: `
    <div class="flex flex-wrap items-start justify-between gap-4">
      <h1 class="text-2xl">Dashboard</h1>
      <p class="m-0 text-right text-sm text-slate-500" aria-live="polite">
        @if (exposure(); as x) { Data as of {{ when(x.asOf) }} · refreshed {{ when(x.generatedAt) }}<br> }
        Built from events; can lag a few seconds behind live claims.
      </p>
    </div>
    @if (error()) { <p class="text-red-700 dark:text-red-300" role="alert">{{ error() }}</p> }

    @if (exposure(); as x) {
      <section class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        @for (t of x.totalsByCurrency; track t.currency) {
          <mat-card appearance="outlined" class="p-4">
            <span class="text-sm text-slate-500">Open exposure · {{ t.currency }}</span>
            <strong class="text-2xl tabular-nums">{{ money(t.exposure, t.currency) }}</strong>
            <span class="text-sm text-slate-500">{{ t.openClaims }} open claim{{ t.openClaims === 1 ? '' : 's' }}</span>
          </mat-card>
        } @empty {
          <mat-card appearance="outlined" class="p-4"><span class="text-sm text-slate-500">Open exposure</span><strong class="text-2xl">—</strong><span class="text-sm text-slate-500">No open claims</span></mat-card>
        }
        @if (workload(); as w) {
          <mat-card appearance="outlined" class="p-4"><span class="text-sm text-slate-500">Waiting for an officer</span>
            <strong class="text-2xl tabular-nums">{{ w.unassignedClaims }}</strong><span class="text-sm text-slate-500">unassigned claims</span></mat-card>
        }
      </section>
      <p class="m-0 text-sm text-slate-500">Totals are per currency. Different currencies are never added together (no FX conversion).</p>

      <div class="grid items-start gap-4 md:grid-cols-2">
        <mat-card appearance="outlined" class="p-4">
          <h2 class="mb-2 text-lg">Exposure by market</h2>
          <table mat-table [dataSource]="x.rows" class="w-full">
            <ng-container matColumnDef="market"><th mat-header-cell *matHeaderCellDef>Market</th><td mat-cell *matCellDef="let r">{{ r.market }}</td></ng-container>
            <ng-container matColumnDef="type"><th mat-header-cell *matHeaderCellDef>Type</th><td mat-cell *matCellDef="let r">{{ r.type }}</td></ng-container>
            <ng-container matColumnDef="open"><th mat-header-cell *matHeaderCellDef class="!text-right">Open</th><td mat-cell *matCellDef="let r" class="!text-right">{{ r.openClaims }}</td></ng-container>
            <ng-container matColumnDef="exposure"><th mat-header-cell *matHeaderCellDef class="!text-right">Exposure</th>
              <td mat-cell *matCellDef="let r" class="!text-right tabular-nums">{{ money(r.exposure, r.currency) }}</td></ng-container>
            <tr mat-header-row *matHeaderRowDef="expCols"></tr><tr mat-row *matRowDef="let row; columns: expCols"></tr>
            <tr class="mat-mdc-row" *matNoDataRow><td class="p-4 text-center text-slate-500" [attr.colspan]="4">No open claims.</td></tr>
          </table>
        </mat-card>

        <mat-card appearance="outlined" class="p-4">
          <h2 class="mb-2 text-lg">Officer workload</h2>
          @if (workload(); as w) {
            <table mat-table [dataSource]="w.officers" class="w-full">
              <ng-container matColumnDef="name"><th mat-header-cell *matHeaderCellDef>Officer</th><td mat-cell *matCellDef="let o">{{ o.assigneeName }}</td></ng-container>
              <ng-container matColumnDef="review"><th mat-header-cell *matHeaderCellDef class="!text-right">Reviewing</th><td mat-cell *matCellDef="let o" class="!text-right">{{ o.underReview }}</td></ng-container>
              <ng-container matColumnDef="info"><th mat-header-cell *matHeaderCellDef class="!text-right">Waiting info</th><td mat-cell *matCellDef="let o" class="!text-right">{{ o.pendingInfo }}</td></ng-container>
              <ng-container matColumnDef="approved"><th mat-header-cell *matHeaderCellDef class="!text-right">Approved</th><td mat-cell *matCellDef="let o" class="!text-right">{{ o.approved }}</td></ng-container>
              <ng-container matColumnDef="open"><th mat-header-cell *matHeaderCellDef class="!text-right">Open</th><td mat-cell *matCellDef="let o" class="!text-right font-bold">{{ o.openClaims }}</td></ng-container>
              <tr mat-header-row *matHeaderRowDef="wlCols"></tr><tr mat-row *matRowDef="let row; columns: wlCols"></tr>
              <tr class="mat-mdc-row" *matNoDataRow><td class="p-4 text-center text-slate-500" [attr.colspan]="5">No officer has picked up a claim yet.</td></tr>
            </table>
          }
        </mat-card>
      </div>

      <mat-card appearance="outlined" class="p-4">
        <h2 class="mb-2 text-lg">Claims by status and market</h2>
        <div class="overflow-x-auto">
          <table class="w-full border-collapse text-sm">
            <thead><tr class="border-b border-slate-200 dark:border-slate-800">
              <th class="p-2 text-left font-medium text-slate-500">Market</th>
              @for (s of order; track s) { <th class="p-2 text-right font-medium text-slate-500">{{ label[s] }}</th> }
            </tr></thead>
            <tbody>
              @for (m of markets(); track m) {
                <tr class="border-b border-slate-100 dark:border-slate-800"><td class="p-2">{{ m }}</td>
                  @for (s of order; track s) { <td class="p-2 text-right tabular-nums">{{ count(m, s) || '·' }}</td> }</tr>
              } @empty { <tr><td class="p-4 text-center text-slate-500" [attr.colspan]="order.length + 1">No claims yet.</td></tr> }
            </tbody>
          </table>
        </div>
      </mat-card>
    } @else if (!error()) { <mat-progress-bar mode="indeterminate" /> }`,
})
export class DashboardComponent {
  private api = inject(ApiService);
  private destroyRef = inject(DestroyRef);
  exposure = signal<ExposureReport | null>(null);
  workload = signal<WorkloadReport | null>(null);
  status = signal<StatusReport | null>(null);
  error = signal<string | null>(null);
  order = ORDER;
  expCols = ['market', 'type', 'open', 'exposure'];
  wlCols = ['name', 'review', 'info', 'approved', 'open'];
  label = STATUS_LABEL;
  money = money; when = when;

  markets = computed(() => [...new Set((this.status()?.rows ?? []).map(r => r.market))].sort());
  count(market: string, s: ClaimStatus) {
    return this.status()?.rows.find(r => r.market === market && r.status === s)?.claims ?? 0;
  }

  constructor() {
    timer(0, 15_000).pipe(
      switchMap(() => forkJoin({ x: this.api.exposure(), w: this.api.workload(), s: this.api.byStatus() })
        .pipe(catchError(e => { this.error.set(problem(e)); return EMPTY; }))),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(({ x, w, s }) => { this.exposure.set(x); this.workload.set(w); this.status.set(s); this.error.set(null); });
  }
}
