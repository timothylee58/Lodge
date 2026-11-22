import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = req.url.startsWith('/api/') ? auth.token() : null;
  const authed = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
  return next(authed).pipe(catchError((err: HttpErrorResponse) => {
    // Expired or revoked token: back to login. (A 401 from the login call itself is just "wrong password".)
    if (err.status === 401 && !req.url.endsWith('/api/auth/login')) auth.logout();
    return throwError(() => err);
  }));
};
