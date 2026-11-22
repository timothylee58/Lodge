import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';

@Component({
  selector: 'cd-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header class="topbar">
      <a class="brand" [routerLink]="home()">ClaimDesk</a>
      @if (auth.session(); as s) {
        <nav aria-label="Main">
          @switch (s.role) {
            @case ('CLAIMANT') { <a routerLink="/claims" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">My claims</a>
                                 <a routerLink="/claims/new" routerLinkActive="active">Report an incident</a> }
            @case ('OFFICER') { <a routerLink="/queue" routerLinkActive="active">Queue</a> }
            @case ('MANAGER') { <a routerLink="/dashboard" routerLinkActive="active">Dashboard</a>
                                <a routerLink="/queue" routerLinkActive="active">Claims</a> }
          }
        </nav>
        <div class="who">
          <span>{{ s.name }} <span class="muted">· {{ roleLabel() }}</span></span>
          <button class="link" type="button" (click)="auth.logout()">Sign out</button>
        </div>
      }
    </header>
    <main class="page"><router-outlet /></main>`,
})
export class AppComponent {
  auth = inject(AuthService);
  home = computed(() => this.auth.homeFor(this.auth.role()));
  roleLabel = computed(() => ({ CLAIMANT: 'Claimant', OFFICER: 'Claims officer', MANAGER: 'Manager' })[this.auth.role() ?? 'CLAIMANT']);
}
