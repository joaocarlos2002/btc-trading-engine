import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService } from './auth.service';

/** Pages behind the login: without a session, go to /login and come back afterwards. */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const toLogin = (reason?: string) =>
    router.createUrlTree(['/login'], {
      queryParams: { returnUrl: state.url === '/' ? null : state.url, reason: reason ?? null },
    });
  return auth.ensureSession().pipe(
    map((session) => (session ? true : toLogin())),
    catchError(() => of(toLogin('unreachable'))),
  );
};

/** The login page itself: someone already logged in goes straight to the dashboard. */
export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.ensureSession().pipe(
    map((session) => (session ? router.createUrlTree(['/']) : true)),
    catchError(() => of(true)),
  );
};
