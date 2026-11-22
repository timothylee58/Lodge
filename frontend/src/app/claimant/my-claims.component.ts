import { TitleCasePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { ClaimSummary } from '../core/models';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { money, problem, when } from '../shared/format';
import { MATERIAL } from '../shared/material';

@Component({
  selector: 'lg-my-claims',
  standalone: true,
  imports: [RouterLink, StatusBadgeComponent, TitleCasePipe, ...MATERIAL],
  template: `
    <div class="flex flex-wrap items-center justify-between gap-4">
      <h1 class="text-2xl">My claims</h1>
      <a mat-flat-button routerLink="/claims/new">Report an incident</a>
    </div>
    @if (error()) { <p class="text-red-700 dark:text-red-300" role="alert">{{ error() }}</p> }
    @if (claims(); as list) {
      @if (list.length === 0) {
        <mat-card appearance="outlined" class="items-center p-10 text-center">
          <h2 class="text-lg">No claims yet</h2>
          <p class="text-slate-500">When something happens, report it here and follow every step until it is settled.</p>
          <a mat-flat-button routerLink="/claims/new">Report an incident</a>
        </mat-card>
      } @else {
        <div class="flex flex-col gap-3">
          @for (c of list; track c.id) {
            <a class="text-inherit no-underline" [routerLink]="['/claims', c.id]">
              <mat-card appearance="outlined" class="p-4 transition hover:shadow-md">
                <div><strong>{{ c.claimNumber }}</strong> <span class="text-slate-500">· {{ c.type | titlecase }} · incident {{ c.incidentDate }}</span></div>
                <p class="my-1 truncate text-slate-500">{{ c.description }}</p>
                <div class="flex flex-wrap items-center gap-4 text-sm">
                  <lg-status [status]="c.status" />
                  @if (c.settledAmount != null) { <span>Paid {{ money(c.settledAmount, c.currency) }}</span> }
                  <span class="text-slate-500">Updated {{ when(c.updatedAt) }}</span>
                </div>
              </mat-card>
            </a>
          }
        </div>
      }
    } @else if (!error()) { <mat-progress-bar mode="indeterminate" /> }`,
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
