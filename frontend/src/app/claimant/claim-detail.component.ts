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
import { MATERIAL } from '../shared/material';

const STEPS: { status: ClaimStatus[]; label: string }[] = [
  { status: ['SUBMITTED'], label: 'Submitted' },
  { status: ['UNDER_REVIEW', 'PENDING_INFO'], label: 'Being assessed' },
  { status: ['APPROVED', 'REJECTED'], label: 'Decision' },
  { status: ['SETTLED'], label: 'Paid' },
];

@Component({
  selector: 'cd-claim-detail',
  standalone: true,
  imports: [FormsModule, RouterLink, StatusBadgeComponent, TimelineComponent, ...MATERIAL],
  template: `
    <a class="text-sm no-underline" routerLink="/claims">← My claims</a>
    @if (detail(); as d) {
      <div class="flex flex-wrap items-center justify-between gap-4">
        <h1 class="text-2xl">{{ d.claim.claimNumber }}</h1>
        <cd-status [status]="d.claim.status" />
      </div>
      <ol class="m-0 flex list-none flex-wrap gap-2 p-0" aria-label="Progress">
        @for (s of steps; track s.label; let i = $index) {
          <li class="min-w-28 flex-1 rounded-lg border px-3 py-2 text-sm"
              [class]="i === stepIndex() ? 'min-w-28 flex-1 rounded-lg border px-3 py-2 text-sm border-blue-600 bg-blue-600 font-semibold text-white'
                     : i < stepIndex() ? 'min-w-28 flex-1 rounded-lg border px-3 py-2 text-sm border-emerald-600 text-emerald-700 dark:text-emerald-300'
                     : 'min-w-28 flex-1 rounded-lg border px-3 py-2 text-sm border-slate-300 text-slate-500 dark:border-slate-700'">{{ s.label }}</li>
        }
      </ol>

      @if (openRequest(); as q) {
        <mat-card appearance="outlined" class="border-amber-400 bg-amber-50 p-4 dark:bg-amber-950/40">
          <h2 class="text-lg">We need more information</h2>
          <p>“{{ q.question }}”</p>
          <form class="flex flex-col gap-1" (ngSubmit)="respond()" #f="ngForm">
            <mat-form-field appearance="outline"><mat-label>Your answer</mat-label>
              <textarea matInput name="answer" rows="4" required maxlength="4000" [(ngModel)]="answer"></textarea></mat-form-field>
            @if (actionError()) { <p class="m-0 text-red-700" role="alert">{{ actionError() }}</p> }
            <div><button mat-flat-button type="submit" [disabled]="busy() || f.invalid">{{ busy() ? 'Sending…' : 'Send answer' }}</button></div>
          </form>
        </mat-card>
      }
      @if (d.claim.status === 'REJECTED') {
        <mat-card appearance="outlined" class="p-4"><h2 class="text-lg">Decision</h2><p>Your claim was not approved. {{ rejectReason() }}</p></mat-card>
      }
      @if (d.claim.status === 'SETTLED') {
        <mat-card appearance="outlined" class="p-4"><h2 class="text-lg">Paid</h2><p>We settled this claim for <strong>{{ money(d.claim.settledAmount, d.claim.currency) }}</strong>.</p></mat-card>
      }

      <div class="grid items-start gap-4 md:grid-cols-2">
        <mat-card appearance="outlined" class="p-4">
          <h2 class="mb-3 text-lg">Details</h2>
          <dl class="m-0 grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1">
            <dt class="text-slate-500">Incident date</dt><dd class="m-0">{{ d.claim.incidentDate }}</dd>
            <dt class="text-slate-500">Type</dt><dd class="m-0">{{ d.claim.type }}</dd>
            <dt class="text-slate-500">Handled by</dt><dd class="m-0">{{ d.claim.assigneeName ?? 'Waiting for a claims officer' }}</dd>
            <dt class="text-slate-500">Reported</dt><dd class="m-0">{{ when(d.claim.createdAt) }}</dd>
          </dl>
          <p class="whitespace-pre-wrap">{{ d.claim.description }}</p>
        </mat-card>
        <mat-card appearance="outlined" class="p-4">
          <h2 class="mb-3 text-lg">Timeline</h2>
          <cd-timeline [entries]="d.timeline" [currency]="d.claim.currency" />
          <p class="m-0 text-sm text-slate-500">Updates automatically.</p>
        </mat-card>
      </div>
    } @else if (error()) { <p class="text-red-700" role="alert">{{ error() }}</p> } @else { <mat-progress-bar mode="indeterminate" /> }`,
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
