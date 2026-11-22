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

type Action = 'info' | 'approve' | 'reject' | 'settle' | 'reserve' | null;

@Component({
  selector: 'cd-workbench',
  standalone: true,
  imports: [FormsModule, RouterLink, StatusBadgeComponent, TimelineComponent],
  template: `
    <a class="back" routerLink="/queue">← Queue</a>
    @if (detail(); as d) {
      <div class="page-head">
        <h1>{{ d.claim.claimNumber }} <span class="muted">· {{ d.claim.market }} {{ d.claim.type }}</span></h1>
        <cd-status [status]="d.claim.status" />
      </div>

      <div class="grid-2">
        <div>
          <section class="card">
            <h2>Claim</h2>
            <dl>
              <dt>Incident date</dt><dd>{{ d.claim.incidentDate }}</dd>
              <dt>Officer</dt><dd>{{ d.claim.assigneeName ?? 'Unassigned' }}</dd>
              <dt>Reserve</dt><dd>{{ money(d.claim.reserveAmount, d.claim.currency) }}</dd>
              @if (d.claim.settledAmount != null) { <dt>Settled</dt><dd>{{ money(d.claim.settledAmount, d.claim.currency) }}</dd> }
              <dt>Reported</dt><dd>{{ when(d.claim.createdAt) }}</dd>
            </dl>
            <p class="pre">{{ d.claim.description }}</p>
          </section>

          @if (d.infoRequests.length) {
            <section class="card">
              <h2>Information requests</h2>
              @for (r of d.infoRequests; track r.id) {
                <div class="qa">
                  <p><strong>Q:</strong> {{ r.question }}</p>
                  <p>@if (r.response) { <strong>A:</strong> {{ r.response }} } @else { <span class="muted">Waiting for the claimant…</span> }</p>
                </div>
              }
            </section>
          }
        </div>

        <div>
          <section class="card">
            <h2>Actions</h2>
            @if (!isOfficer()) {
              <p class="muted">Read-only: managers can view claims but not act on them.</p>
            } @else if (d.claim.status === 'SUBMITTED') {
              <p>This claim is waiting for an officer.</p>
              <button class="primary" [disabled]="busy()" (click)="run(api.assign(id()))">Pick up this claim</button>
            } @else if (!isMine()) {
              <p class="muted">Assigned to {{ d.claim.assigneeName }}. Only they can act on it.</p>
            } @else if (d.claim.status === 'SETTLED' || d.claim.status === 'REJECTED') {
              <p class="muted">This claim is closed.</p>
            } @else {
              <div class="action-buttons">
                @if (d.claim.status === 'UNDER_REVIEW') {
                  <button (click)="open('info')">Request information</button>
                  <button (click)="open('approve')">Approve</button>
                  <button class="danger" (click)="open('reject')">Reject</button>
                }
                @if (d.claim.status === 'APPROVED') { <button class="primary" (click)="open('settle')">Settle</button> }
                <button (click)="open('reserve')">Adjust reserve</button>
              </div>
              @if (d.claim.status === 'PENDING_INFO') { <p class="muted">Waiting for the claimant to answer.</p> }

              @switch (action()) {
                @case ('info') {
                  <form (ngSubmit)="run(api.requestInfo(id(), text.trim()))" #f="ngForm" class="action-form">
                    <label>Question for the claimant <textarea name="q" rows="3" required maxlength="2000" [(ngModel)]="text"></textarea></label>
                    <div class="actions"><button type="button" (click)="open(null)">Cancel</button><button class="primary" [disabled]="busy() || f.invalid">Send request</button></div>
                  </form>
                }
                @case ('approve') {
                  <form (ngSubmit)="run(api.approve(id(), amount!))" #f="ngForm" class="action-form">
                    <label>Reserve ({{ d.claim.currency }}) <input name="a" type="number" min="0" step="0.01" required [(ngModel)]="amount"></label>
                    <p class="hint">The amount set aside for this claim. It counts towards exposure until the claim is settled.</p>
                    <div class="actions"><button type="button" (click)="open(null)">Cancel</button><button class="primary" [disabled]="busy() || f.invalid">Approve</button></div>
                  </form>
                }
                @case ('reject') {
                  <form (ngSubmit)="run(api.reject(id(), text.trim()))" #f="ngForm" class="action-form">
                    <label>Reason (shown to the claimant) <textarea name="r" rows="3" required maxlength="500" [(ngModel)]="text"></textarea></label>
                    <div class="actions"><button type="button" (click)="open(null)">Cancel</button><button class="danger" [disabled]="busy() || f.invalid">Reject claim</button></div>
                  </form>
                }
                @case ('settle') {
                  <form (ngSubmit)="run(api.settle(id(), amount!))" #f="ngForm" class="action-form">
                    <label>Settlement amount ({{ d.claim.currency }}) <input name="a" type="number" min="0" step="0.01" required [(ngModel)]="amount"></label>
                    <p class="hint">Cannot exceed the policy's coverage limit. Settling closes the claim.</p>
                    <div class="actions"><button type="button" (click)="open(null)">Cancel</button><button class="primary" [disabled]="busy() || f.invalid">Settle claim</button></div>
                  </form>
                }
                @case ('reserve') {
                  <form (ngSubmit)="run(api.adjustReserve(id(), amount!))" #f="ngForm" class="action-form">
                    <label>New reserve ({{ d.claim.currency }}) <input name="a" type="number" min="0" step="0.01" required [(ngModel)]="amount"></label>
                    <div class="actions"><button type="button" (click)="open(null)">Cancel</button><button class="primary" [disabled]="busy() || f.invalid">Save reserve</button></div>
                  </form>
                }
              }
            }
            @if (actionError()) { <p class="error" role="alert">{{ actionError() }}</p> }
          </section>

          <section class="card">
            <h2>Timeline</h2>
            <cd-timeline [entries]="d.timeline" [currency]="d.claim.currency" />
          </section>
        </div>
      </div>
    } @else if (error()) { <p class="error" role="alert">{{ error() }}</p> } @else { <p class="muted">Loading…</p> }`,
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
