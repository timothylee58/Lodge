import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, catchError, forkJoin, switchMap, timer } from 'rxjs';
import { ApiService } from '../core/api.service';
import { ClaimStatus, ExposureReport, StatusReport, WorkloadReport } from '../core/models';
import { STATUS_LABEL, money, problem, when } from '../shared/format';

const ORDER: ClaimStatus[] = ['SUBMITTED', 'UNDER_REVIEW', 'PENDING_INFO', 'APPROVED', 'SETTLED', 'REJECTED'];

@Component({
  selector: 'cd-dashboard',
  standalone: true,
  template: `
    <div class="page-head">
      <h1>Dashboard</h1>
      <p class="muted small" aria-live="polite">
        @if (exposure(); as x) {
          Data as of {{ when(x.asOf) }} · refreshed {{ when(x.generatedAt) }}
        }
        <br>Reports are built from events and can lag a few seconds behind live claims.
      </p>
    </div>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }

    @if (exposure(); as x) {
      <section class="kpis">
        @for (t of x.totalsByCurrency; track t.currency) {
          <div class="card kpi">
            <span class="muted">Open exposure · {{ t.currency }}</span>
            <strong>{{ money(t.exposure, t.currency) }}</strong>
            <span class="muted">{{ t.openClaims }} open claim{{ t.openClaims === 1 ? '' : 's' }}</span>
          </div>
        } @empty {
          <div class="card kpi"><span class="muted">Open exposure</span><strong>—</strong><span class="muted">No open claims</span></div>
        }
        @if (workload(); as w) {
          <div class="card kpi"><span class="muted">Waiting for an officer</span><strong>{{ w.unassignedClaims }}</strong><span class="muted">unassigned claims</span></div>
        }
      </section>
      <p class="hint">Totals are per currency. Different currencies are never added together (no FX conversion).</p>

      <div class="grid-2">
        <section class="card">
          <h2>Exposure by market</h2>
          <div class="table-wrap"><table>
            <thead><tr><th>Market</th><th>Type</th><th class="num">Open</th><th class="num">Exposure</th></tr></thead>
            <tbody>
              @for (r of x.rows; track r.market + r.type + r.currency) {
                <tr><td>{{ r.market }}</td><td>{{ r.type }}</td><td class="num">{{ r.openClaims }}</td><td class="num">{{ money(r.exposure, r.currency) }}</td></tr>
              } @empty { <tr><td colspan="4" class="muted center">No open claims.</td></tr> }
            </tbody>
          </table></div>
        </section>

        <section class="card">
          <h2>Officer workload</h2>
          @if (workload(); as w) {
            <div class="table-wrap"><table>
              <thead><tr><th>Officer</th><th class="num">Reviewing</th><th class="num">Waiting info</th><th class="num">Approved</th><th class="num">Open</th></tr></thead>
              <tbody>
                @for (o of w.officers; track o.assigneeId) {
                  <tr><td>{{ o.assigneeName }}</td><td class="num">{{ o.underReview }}</td><td class="num">{{ o.pendingInfo }}</td><td class="num">{{ o.approved }}</td><td class="num"><strong>{{ o.openClaims }}</strong></td></tr>
                } @empty { <tr><td colspan="5" class="muted center">No officer has picked up a claim yet.</td></tr> }
              </tbody>
            </table></div>
          }
        </section>
      </div>

      <section class="card">
        <h2>Claims by status and market</h2>
        <div class="table-wrap"><table>
          <thead><tr><th>Market</th>@for (s of order; track s) { <th class="num">{{ label[s] }}</th> }</tr></thead>
          <tbody>
            @for (m of markets(); track m) {
              <tr><td>{{ m }}</td>@for (s of order; track s) { <td class="num">{{ count(m, s) || '·' }}</td> }</tr>
            } @empty { <tr><td [attr.colspan]="order.length + 1" class="muted center">No claims yet.</td></tr> }
          </tbody>
        </table></div>
      </section>
    } @else if (!error()) { <p class="muted">Loading…</p> }`,
})
export class DashboardComponent {
  private api = inject(ApiService);
  private destroyRef = inject(DestroyRef);
  exposure = signal<ExposureReport | null>(null);
  workload = signal<WorkloadReport | null>(null);
  status = signal<StatusReport | null>(null);
  error = signal<string | null>(null);
  order = ORDER;
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
