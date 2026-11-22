import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Role } from './models';
import { AuthService } from './auth.service';

/** UX only: the backend enforces every role and ownership rule independently. */
export const requireRole = (...roles: Role[]): CanActivateFn => () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const role = auth.role();
  if (!role) return router.parseUrl('/login');
  return roles.includes(role) ? true : router.parseUrl(auth.homeFor(role));
};

export const homeRedirect: CanActivateFn = () => {
  const auth = inject(AuthService);
  return inject(Router).parseUrl(auth.homeFor(auth.role()));
};
