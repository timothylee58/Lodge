import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from './core/auth.service';
import { problem } from './shared/format';
import { MATERIAL } from './shared/material';

@Component({
  selector: 'cd-login',
  standalone: true,
  imports: [FormsModule, ...MATERIAL],
  template: `
    <mat-card class="mx-auto mt-8 w-full max-w-md" appearance="outlined">
      <mat-card-header><mat-card-title><h1 class="text-2xl">Sign in</h1></mat-card-title></mat-card-header>
      <mat-card-content>
        <form class="mt-4 flex flex-col gap-1" (ngSubmit)="submit()" #f="ngForm">
          <mat-form-field appearance="outline"><mat-label>Email</mat-label>
            <input matInput name="email" type="email" autocomplete="username" required [(ngModel)]="email"></mat-form-field>
          <mat-form-field appearance="outline"><mat-label>Password</mat-label>
            <input matInput name="password" type="password" autocomplete="current-password" required [(ngModel)]="password"></mat-form-field>
          @if (error()) { <p class="m-0 mb-2 text-red-700 dark:text-red-300" role="alert">{{ error() }}</p> }
          <button mat-flat-button type="submit" [disabled]="busy() || f.invalid">{{ busy() ? 'Signing in…' : 'Sign in' }}</button>
        </form>
        <details class="mt-6 text-sm">
          <summary class="cursor-pointer">Demo accounts (local only, password <code>password</code>)</summary>
          <div class="mt-2 flex flex-col items-start">
            @for (d of demo; track d.email) {
              <button mat-button type="button" (click)="email = d.email; password = 'password'">{{ d.email }} · {{ d.role }}</button>
            }
          </div>
        </details>
      </mat-card-content>
    </mat-card>`,
})
export class LoginComponent {
  private auth = inject(AuthService);
  private router = inject(Router);
  email = '';
  password = '';
  busy = signal(false);
  error = signal<string | null>(null);
  demo = [
    { email: 'aina@demo.local', role: 'Claimant' }, { email: 'wei@demo.local', role: 'Officer (MY)' },
    { email: 'priya@demo.local', role: 'Officer (SG)' }, { email: 'mark@demo.local', role: 'Manager' },
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
