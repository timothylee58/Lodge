import { Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EMPTY, catchError, switchMap, timer } from 'rxjs';
import { ApiService } from '../core/api.service';
import { ClaimDetail, ClaimStatus } from '../core/models';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { TimelineComponent } from '../shared/timeline.component';
import { money, problem, when } from '../shared/format';

const STEPS: { status: ClaimStatus[]; label: string }[] = [
  { status: ['SUBMITTED'], label: 'Submitted' },
  { status: ['UNDER_REVIEW', 'PENDING_INFO'], label: 'Being assessed' },
  { status: ['APPROVED', 'REJECTED'], label: 'Decision' },
  { status: ['SETTLED'], label: 'Paid' },
];

@Component({
  selector: 'cd-claim-detail',
  standalone: true,
  imports: [FormsModule, RouterLink, StatusBadgeComponent, TimelineComponent],
  template: `
    <a class="back" routerLink="/claims">← My claims</a>
    @if (detail(); as d) {
      <div class="page-head">
        <h1>{{ d.claim.claimNumber }}</h1>
        <cd-status [status]="d.claim.status" />
      </div>
      <ol class="steps" aria-label="Progress">
        @for (s of steps; track s.label; let i = $index) {
          <li [class.done]="i < stepIndex()" [class.current]="i === stepIndex()">{{ s.label }}</li>
        }
      </ol>

      @if (openRequest(); as q) {
        <section class="card attention">
          <h2>We need more information</h2>
          <p>“{{ q.question }}”</p>
          <form (ngSubmit)="respond()" #f="ngForm">
            <label>Your answer <textarea name="answer" rows="4" required maxlength="4000" [(ngModel)]="answer"></textarea></label>
            @if (actionError()) { <p class="error" role="alert">{{ actionError() }}</p> }
            <button class="primary" type="submit" [disabled]="busy() || f.invalid">{{ busy() ? 'Sending…' : 'Send answer' }}</button>
          </form>
        </section>
      }
      @if (d.claim.status === 'REJECTED') {
        <section class="card"><h2>Decision</h2><p>Your claim was not approved. {{ rejectReason() }}</p></section>
      }
      @if (d.claim.status === 'SETTLED') {
        <section class="card"><h2>Paid</h2><p>We settled this claim for <strong>{{ money(d.claim.settledAmount, d.claim.currency) }}</strong>.</p></section>
      }

      <div class="grid-2">
        <section class="card">
          <h2>Details</h2>
          <dl>
            <dt>Incident date</dt><dd>{{ d.claim.incidentDate }}</dd>
            <dt>Type</dt><dd>{{ d.claim.type }}</dd>
            <dt>Handled by</dt><dd>{{ d.claim.assigneeName ?? 'Waiting for a claims officer' }}</dd>
            <dt>Reported</dt><dd>{{ when(d.claim.createdAt) }}</dd>
          </dl>
          <p class="pre">{{ d.claim.description }}</p>
        </section>
        <section class="card">
          <h2>Timeline</h2>
          <cd-timeline [entries]="d.timeline" [currency]="d.claim.currency" />
          <p class="hint">Updates automatically.</p>
        </section>
      </div>
    } @else if (error()) { <p class="error" role="alert">{{ error() }}</p> } @else { <p class="muted">Loading…</p> }`,
})
export class ClaimDetailComponent {
  id = input.required<string>();
  private api = inject(ApiService);
  private destroyRef = inject(DestroyRef);
  detail = signal<ClaimDetail | null>(null);
  error = signal<string | null>(null);
  actionError = signal<string | null>(null);
  busy = signal(false);
  answer = '';
  steps = STEPS;
  money = money; when = when;

  openRequest = computed(() => this.detail()?.claim.status === 'PENDING_INFO'
    ? this.detail()!.infoRequests.find(r => r.status === 'OPEN') ?? null : null);
  stepIndex = computed(() => {
    const s = this.detail()?.claim.status;
    return s ? STEPS.findIndex(x => x.status.includes(s)) : 0;
  });
  rejectReason = computed(() => {
    const e = this.detail()?.timeline.find(t => t.type === 'CLAIM_REJECTED');
    return e && typeof e.detail['reason'] === 'string' ? `Reason: ${e.detail['reason']}` : '';
  });

  ngOnInit() {
    // Polling keeps the claimant timeline fresh (officer actions). Next step: SSE.
    // A failed poll shows the error but keeps polling.
    timer(0, 10_000).pipe(
      switchMap(() => this.api.claim(this.id()).pipe(catchError(e => { this.error.set(problem(e)); return EMPTY; }))),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(d => { this.detail.set(d); this.error.set(null); });
  }

  respond() {
    this.busy.set(true);
    this.actionError.set(null);
    this.api.respond(this.id(), this.answer.trim()).subscribe({
      next: d => { this.detail.set(d); this.answer = ''; this.busy.set(false); },
      error: e => { this.actionError.set(problem(e)); this.busy.set(false); },
    });
  }
}
