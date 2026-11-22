import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { Policy } from '../core/models';
import { money, problem, todayIso } from '../shared/format';
import { MATERIAL } from '../shared/material';

@Component({
  selector: 'cd-submit-claim',
  standalone: true,
  imports: [FormsModule, RouterLink, ...MATERIAL],
  template: `
    <h1 class="text-2xl">Report an incident</h1>
    <mat-card appearance="outlined" class="w-full max-w-xl p-4">
      @if (policies(); as list) {
        @if (list.length === 0) {
          <p>You have no active policies to claim against. Contact us if you think this is wrong.</p>
        } @else {
          <form class="flex flex-col gap-1" (ngSubmit)="submit()" #f="ngForm">
            <mat-form-field appearance="outline"><mat-label>Policy</mat-label>
              <mat-select name="policy" required [(ngModel)]="policyId">
                @for (p of list; track p.id) {
                  <mat-option [value]="p.id">{{ p.policyNumber }} — {{ p.type }} (cover up to {{ money(p.coverageLimit, p.currency) }})</mat-option>
                }
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Date of the incident</mat-label>
              <input matInput name="date" type="date" required [max]="today" [(ngModel)]="incidentDate"></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>What happened?</mat-label>
              <textarea matInput name="description" rows="6" required maxlength="4000" [(ngModel)]="description"
                placeholder="Where and when it happened, what was damaged, and anyone else involved."></textarea>
              <mat-hint align="end">{{ description.length }} / 4000</mat-hint>
            </mat-form-field>
            @if (error()) { <p class="m-0 text-red-700 dark:text-red-300" role="alert">{{ error() }}</p> }
            <div class="mt-2 flex justify-end gap-2">
              <a mat-button routerLink="/claims">Cancel</a>
              <button mat-flat-button type="submit" [disabled]="busy() || f.invalid">{{ busy() ? 'Submitting…' : 'Submit claim' }}</button>
            </div>
          </form>
        }
      } @else if (error()) { <p class="text-red-700" role="alert">{{ error() }}</p> } @else { <mat-progress-bar mode="indeterminate" /> }
    </mat-card>`,
})
export class SubmitClaimComponent {
  private api = inject(ApiService);
  private router = inject(Router);
  policies = signal<Policy[] | null>(null);
  error = signal<string | null>(null);
  busy = signal(false);
  policyId = '';
  incidentDate = todayIso();
  description = '';
  today = todayIso();
  money = money;

  constructor() {
    this.api.myPolicies().subscribe({
      next: l => { this.policies.set(l); this.policyId = l[0]?.id ?? ''; },
      error: e => this.error.set(problem(e)),
    });
  }

  submit() {
    this.busy.set(true);
    this.error.set(null);
    this.api.submit({ policyId: this.policyId, incidentDate: this.incidentDate, description: this.description.trim() }).subscribe({
      next: d => this.router.navigate(['/claims', d.claim.id]),
      error: e => { this.error.set(problem(e)); this.busy.set(false); },
    });
  }
}
