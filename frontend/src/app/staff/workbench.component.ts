import { Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EMPTY, Observable, catchError, switchMap, timer } from 'rxjs';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { ClaimDetail } from '../core/models';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { TimelineComponent } from '../shared/timeline.component';
import { money, problem, when } from '../shared/format';
import { MATERIAL } from '../shared/material';

type Action = 'info' | 'approve' | 'reject' | 'settle' | 'reserve' | null;

@Component({
  selector: 'cd-workbench',
  standalone: true,
  imports: [FormsModule, RouterLink, StatusBadgeComponent, TimelineComponent, ...MATERIAL],
  template: `
    <a class="text-sm no-underline" routerLink="/queue">← Queue</a>
    @if (detail(); as d) {
      <div class="flex flex-wrap items-center justify-between gap-4">
        <h1 class="text-2xl">{{ d.claim.claimNumber }} <span class="text-slate-500">· {{ d.claim.market }} {{ d.claim.type }}</span></h1>
        <cd-status [status]="d.claim.status" />
      </div>
      @if (busy()) { <mat-progress-bar mode="indeterminate" /> }

      <div class="grid items-start gap-4 md:grid-cols-2">
        <div class="flex flex-col gap-4">
          <mat-card appearance="outlined" class="p-4">
            <h2 class="mb-3 text-lg">Claim</h2>
            <dl class="m-0 grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1">
              <dt class="text-slate-500">Incident date</dt><dd class="m-0">{{ d.claim.incidentDate }}</dd>
              <dt class="text-slate-500">Officer</dt><dd class="m-0">{{ d.claim.assigneeName ?? 'Unassigned' }}</dd>
              <dt class="text-slate-500">Reserve</dt><dd class="m-0 tabular-nums">{{ money(d.claim.reserveAmount, d.claim.currency) }}</dd>
              @if (d.claim.settledAmount != null) { <dt class="text-slate-500">Settled</dt><dd class="m-0 tabular-nums">{{ money(d.claim.settledAmount, d.claim.currency) }}</dd> }
              <dt class="text-slate-500">Reported</dt><dd class="m-0">{{ when(d.claim.createdAt) }}</dd>
            </dl>
            <p class="whitespace-pre-wrap">{{ d.claim.description }}</p>
          </mat-card>

          @if (d.infoRequests.length) {
            <mat-card appearance="outlined" class="p-4">
              <h2 class="mb-2 text-lg">Information requests</h2>
              @for (r of d.infoRequests; track r.id) {
                <div class="border-slate-200 py-2 [&+&]:border-t dark:border-slate-800">
                  <p class="m-0"><strong>Q:</strong> {{ r.question }}</p>
                  <p class="m-0">@if (r.response) { <strong>A:</strong> {{ r.response }} } @else { <span class="text-slate-500">Waiting for the claimant…</span> }</p>
                </div>
              }
            </mat-card>
          }
        </div>

        <div class="flex flex-col gap-4">
          <mat-card appearance="outlined" class="p-4">
            <h2 class="mb-3 text-lg">Actions</h2>
            @if (!isOfficer()) {
              <p class="text-slate-500">Read-only: managers can view claims but not act on them.</p>
            } @else if (d.claim.status === 'SUBMITTED') {
              <p>This claim is waiting for an officer.</p>
              <div><button mat-flat-button [disabled]="busy()" (click)="run(api.assign(id()))">Pick up this claim</button></div>
            } @else if (!isMine()) {
              <p class="text-slate-500">Assigned to {{ d.claim.assigneeName }}. Only they can act on it.</p>
            } @else if (d.claim.status === 'SETTLED' || d.claim.status === 'REJECTED') {
              <p class="text-slate-500">This claim is closed.</p>
            } @else {
              <div class="flex flex-wrap gap-2">
                @if (d.claim.status === 'UNDER_REVIEW') {
                  <button mat-stroked-button (click)="open('info')">Request information</button>
                  <button mat-stroked-button (click)="open('approve')">Approve</button>
                  <button mat-stroked-button class="!text-red-700 dark:!text-red-300" (click)="open('reject')">Reject</button>
                }
                @if (d.claim.status === 'APPROVED') { <button mat-flat-button (click)="open('settle')">Settle</button> }
                <button mat-stroked-button (click)="open('reserve')">Adjust reserve</button>
              </div>
              @if (d.claim.status === 'PENDING_INFO') { <p class="text-slate-500">Waiting for the claimant to answer.</p> }

              @if (action(); as a) {
                <form class="mt-4 flex flex-col gap-1 border-t border-slate-200 pt-4 dark:border-slate-800" (ngSubmit)="submitAction(a)" #f="ngForm">
                  @switch (a) {
                    @case ('info') {
                      <mat-form-field appearance="outline"><mat-label>Question for the claimant</mat-label>
                        <textarea matInput name="q" rows="3" required maxlength="2000" [(ngModel)]="text"></textarea></mat-form-field>
                    }
                    @case ('reject') {
                      <mat-form-field appearance="outline"><mat-label>Reason (shown to the claimant)</mat-label>
                        <textarea matInput name="r" rows="3" required maxlength="500" [(ngModel)]="text"></textarea></mat-form-field>
                    }
                    @default {
                      <mat-form-field appearance="outline"><mat-label>{{ amountLabel(a) }} ({{ d.claim.currency }})</mat-label>
                        <input matInput name="a" type="number" min="0" step="0.01" required [(ngModel)]="amount">
                        <mat-hint>{{ a === 'settle' ? 'Cannot exceed the policy coverage limit. Settling closes the claim.' : 'Set aside for this claim; counts towards exposure until settled.' }}</mat-hint>
                      </mat-form-field>
                    }
                  }
                  <div class="mt-2 flex justify-end gap-2">
                    <button mat-button type="button" (click)="open(null)">Cancel</button>
                    <button mat-flat-button type="submit" [disabled]="busy() || f.invalid">{{ submitLabel(a) }}</button>
                  </div>
                </form>
              }
            }
            @if (actionError()) { <p class="m-0 mt-2 text-red-700 dark:text-red-300" role="alert">{{ actionError() }}</p> }
          </mat-card>

          <mat-card appearance="outlined" class="p-4">
            <h2 class="mb-3 text-lg">Timeline</h2>
            <cd-timeline [entries]="d.timeline" [currency]="d.claim.currency" />
          </mat-card>
        </div>
      </div>
    } @else if (error()) { <p class="text-red-700" role="alert">{{ error() }}</p> } @else { <mat-progress-bar mode="indeterminate" /> }`,
})
export class WorkbenchComponent {
  id = input.required<string>();
  api = inject(ApiService);
  private auth = inject(AuthService);
  private destroyRef = inject(DestroyRef);
  detail = signal<ClaimDetail | null>(null);
  error = signal<string | null>(null);
  actionError = signal<string | null>(null);
  action = signal<Action>(null);
  busy = signal(false);
  text = '';
  amount: number | null = null;
  money = money; when = when;

  isOfficer = computed(() => this.auth.role() === 'OFFICER');
  isMine = computed(() => this.detail()?.claim.assigneeId === this.auth.session()?.userId);

  ngOnInit() {
    timer(0, 15_000).pipe(
      switchMap(() => this.api.claim(this.id()).pipe(catchError(e => { this.error.set(problem(e)); return EMPTY; }))),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(d => { if (!this.busy()) this.detail.set(d); });
  }

  open(a: Action) {
    this.action.set(a);
    this.actionError.set(null);
    this.text = '';
    this.amount = a === 'reserve' || a === 'settle' ? this.detail()?.claim.reserveAmount ?? null : null;
  }

  amountLabel(a: Action) { return a === 'approve' ? 'Reserve' : a === 'settle' ? 'Settlement amount' : 'New reserve'; }
  submitLabel(a: Action) {
    return ({ info: 'Send request', approve: 'Approve', reject: 'Reject claim', settle: 'Settle claim', reserve: 'Save reserve' } as const)[a!];
  }
  submitAction(a: Action) {
    const id = this.id();
    switch (a) {
      case 'info': return this.run(this.api.requestInfo(id, this.text.trim()));
      case 'reject': return this.run(this.api.reject(id, this.text.trim()));
      case 'approve': return this.run(this.api.approve(id, this.amount!));
      case 'settle': return this.run(this.api.settle(id, this.amount!));
      case 'reserve': return this.run(this.api.adjustReserve(id, this.amount!));
      default: return;
    }
  }

  run(call: Observable<ClaimDetail>) {
    this.busy.set(true);
    this.actionError.set(null);
    call.subscribe({
      next: d => { this.detail.set(d); this.action.set(null); this.busy.set(false); },
      error: e => {
        this.busy.set(false);
        this.actionError.set(e.status === 409 ? `${problem(e)} The claim has been refreshed.` : problem(e));
        if (e.status === 409) this.api.claim(this.id()).subscribe(d => this.detail.set(d));
      },
    });
  }
}
