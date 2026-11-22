import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, map, tap } from 'rxjs';
import { Role, Session } from './models';

const KEY = 'claimdesk.session';

/**
 * sessionStorage (cleared when the tab closes) rather than localStorage. A bearer token in JS-readable storage is
 * exposed to XSS; Angular's template escaping and the CSP in nginx.conf are the mitigation. Production path is an
 * OIDC code+PKCE flow (see docs/adr/0002).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private router = inject(Router);
  private state = signal<Session | null>(this.restore());

  readonly session = this.state.asReadonly();
  readonly isLoggedIn = computed(() => this.state() !== null);
  readonly role = computed(() => this.state()?.role ?? null);

  login(email: string, password: string): Observable<Session> {
    return this.http.post<{ token: string; expiresInSeconds: number; userId: string; name: string; role: Role }>(
      '/api/auth/login', { email, password }).pipe(
      map(r => ({ token: r.token, userId: r.userId, name: r.name, role: r.role, expiresAt: Date.now() + r.expiresInSeconds * 1000 })),
      tap(s => { this.state.set(s); sessionStorage.setItem(KEY, JSON.stringify(s)); }));
  }

  token(): string | null {
    const s = this.state();
    if (s && s.expiresAt <= Date.now()) { this.logout(); return null; }
    return s?.token ?? null;
  }

  logout(): void {
    this.state.set(null);
    sessionStorage.removeItem(KEY);
    this.router.navigateByUrl('/login');
  }

  homeFor(role: Role | null): string {
    return role === 'CLAIMANT' ? '/claims' : role === 'OFFICER' ? '/queue' : role === 'MANAGER' ? '/dashboard' : '/login';
  }

  private restore(): Session | null {
    try {
      const s = JSON.parse(sessionStorage.getItem(KEY) ?? 'null') as Session | null;
      return s && s.expiresAt > Date.now() ? s : null;
    } catch { return null; }
  }
}
