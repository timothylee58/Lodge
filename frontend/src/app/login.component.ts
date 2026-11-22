import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from './core/auth.service';
import { problem } from './shared/format';

@Component({
  selector: 'cd-login',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="card narrow">
      <h1>Sign in</h1>
      <form (ngSubmit)="submit()" #f="ngForm">
        <label>Email <input name="email" type="email" autocomplete="username" required [(ngModel)]="email"></label>
        <label>Password <input name="password" type="password" autocomplete="current-password" required [(ngModel)]="password"></label>
        @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
        <button class="primary" type="submit" [disabled]="busy() || f.invalid">{{ busy() ? 'Signing in…' : 'Sign in' }}</button>
      </form>
      <details class="demo">
        <summary>Demo accounts (local only, password <code>password</code>)</summary>
        <ul>
          @for (d of demo; track d.email) {
            <li><button class="link" type="button" (click)="email = d.email; password = 'password'">{{ d.email }}</button> <span class="muted">{{ d.role }}</span></li>
          }
        </ul>
      </details>
    </section>`,
})
export class LoginComponent {
  private auth = inject(AuthService);
  private router = inject(Router);
  email = '';
  password = '';
  busy = signal(false);
  error = signal<string | null>(null);
  demo = [
    { email: 'aina@demo.local', role: 'Claimant' }, { email: 'wei@demo.local', role: 'Claims officer (MY)' },
    { email: 'priya@demo.local', role: 'Claims officer (SG)' }, { email: 'mark@demo.local', role: 'Manager' },
  ];

  submit() {
    this.busy.set(true);
    this.error.set(null);
    this.auth.login(this.email.trim(), this.password).subscribe({
      next: s => this.router.navigateByUrl(this.auth.homeFor(s.role)),
      error: e => { this.error.set(e.status === 401 ? 'Email or password is incorrect.' : problem(e)); this.busy.set(false); },
    });
  }
}
