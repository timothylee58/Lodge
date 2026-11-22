import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { Policy } from '../core/models';
import { money, problem, todayIso } from '../shared/format';

@Component({
  selector: 'cd-submit-claim',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <h1>Report an incident</h1>
    <section class="card narrow">
      @if (policies(); as list) {
        @if (list.length === 0) {
          <p>You have no active policies to claim against. Contact us if you think this is wrong.</p>
        } @else {
          <form (ngSubmit)="submit()" #f="ngForm">
            <label>Policy
              <select name="policy" required [(ngModel)]="policyId">
                @for (p of list; track p.id) {
                  <option [value]="p.id">{{ p.policyNumber }} — {{ p.type }} (cover up to {{ money(p.coverageLimit, p.currency) }})</option>
                }
              </select>
            </label>
            <label>Date of the incident
              <input name="date" type="date" required [max]="today" [(ngModel)]="incidentDate">
            </label>
            <label>What happened?
              <textarea name="description" rows="6" required maxlength="4000" [(ngModel)]="description"
                placeholder="Where and when it happened, what was damaged, and anyone else involved."></textarea>
              <span class="hint">{{ description.length }} / 4000</span>
            </label>
            @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
            <div class="actions">
              <a class="button" routerLink="/claims">Cancel</a>
              <button class="primary" type="submit" [disabled]="busy() || f.invalid">{{ busy() ? 'Submitting…' : 'Submit claim' }}</button>
            </div>
          </form>
        }
      } @else if (error()) { <p class="error" role="alert">{{ error() }}</p> } @else { <p class="muted">Loading…</p> }
    </section>`,
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
