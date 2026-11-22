import { Component, inject, signal } from '@angular/core';
import { TitleCasePipe } from "@angular/common";
import { RouterLink } from "@angular/router";
import { ApiService } from '../core/api.service';
import { ClaimSummary } from '../core/models';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { money, problem, when } from '../shared/format';

@Component({
  selector: 'cd-my-claims',
  standalone: true,
  imports: [RouterLink, StatusBadgeComponent, TitleCasePipe],
  template: `
    <div class="page-head">
      <h1>My claims</h1>
      <a class="button primary" routerLink="/claims/new">Report an incident</a>
    </div>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (claims(); as list) {
      @if (list.length === 0) {
        <section class="card empty">
          <h2>No claims yet</h2>
          <p class="muted">When something happens, report it here and follow every step until it is settled.</p>
          <a class="button primary" routerLink="/claims/new">Report an incident</a>
        </section>
      } @else {
        <ul class="claim-list">
          @for (c of list; track c.id) {
            <li class="card row-link">
              <a [routerLink]="['/claims', c.id]">
                <div><strong>{{ c.claimNumber }}</strong> <span class="muted">· {{ c.type | titlecase }} · incident {{ c.incidentDate }}</span></div>
                <p class="muted clip">{{ c.description }}</p>
                <div class="row-meta">
                  <cd-status [status]="c.status" />
                  @if (c.settledAmount != null) { <span>Paid {{ money(c.settledAmount, c.currency) }}</span> }
                  <span class="muted">Updated {{ when(c.updatedAt) }}</span>
                </div>
              </a>
            </li>
          }
        </ul>
      }
    } @else if (!error()) { <p class="muted">Loading…</p> }`,
})
export class MyClaimsComponent {
  private api = inject(ApiService);
  claims = signal<ClaimSummary[] | null>(null);
  error = signal<string | null>(null);
  money = money; when = when;

  constructor() {
    this.api.myClaims().subscribe({ next: l => this.claims.set(l), error: e => this.error.set(problem(e)) });
  }
}
