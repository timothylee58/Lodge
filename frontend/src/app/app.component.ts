import { Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';

@Component({
  selector: 'cd-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatToolbarModule, MatButtonModule],
  template: `
    <mat-toolbar class="flex flex-wrap gap-2 !h-auto min-h-14 border-b border-slate-200 dark:border-slate-800">
      <a class="mr-4 font-bold text-inherit no-underline" [routerLink]="home()">ClaimDesk</a>
      @if (auth.session(); as s) {
        <nav aria-label="Main" class="flex flex-1 gap-1">
          @switch (s.role) {
            @case ('CLAIMANT') {
              <a mat-button routerLink="/claims" routerLinkActive="!font-bold" [routerLinkActiveOptions]="{ exact: true }">My claims</a>
              <a mat-button routerLink="/claims/new" routerLinkActive="!font-bold">Report an incident</a>
            }
            @case ('OFFICER') { <a mat-button routerLink="/queue" routerLinkActive="!font-bold">Queue</a> }
            @case ('MANAGER') {
              <a mat-button routerLink="/dashboard" routerLinkActive="!font-bold">Dashboard</a>
              <a mat-button routerLink="/queue" routerLinkActive="!font-bold">Claims</a>
            }
          }
        </nav>
        <span class="text-sm">{{ s.name }} <span class="opacity-70">· {{ roleLabel() }}</span></span>
        <button mat-button type="button" (click)="auth.logout()">Sign out</button>
      }
    </mat-toolbar>
    <main class="mx-auto flex max-w-6xl flex-col gap-4 px-4 pb-16 pt-6"><router-outlet /></main>`,
})
export class AppComponent {
  auth = inject(AuthService);
  home = computed(() => this.auth.homeFor(this.auth.role()));
  roleLabel = computed(() => ({ CLAIMANT: 'Claimant', OFFICER: 'Claims officer', MANAGER: 'Manager' })[this.auth.role() ?? 'CLAIMANT']);
}
