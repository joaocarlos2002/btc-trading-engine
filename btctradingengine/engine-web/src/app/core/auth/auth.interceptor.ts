import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/** A 401 from any API call means the session is gone: back to the login, returning here afterwards. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return next(request).pipe(
    catchError((error: unknown) => {
      const sessionEnded =
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        !request.url.startsWith('/api/auth/');
      if (sessionEnded && !router.url.startsWith('/login')) {
        auth.expire();
        void router.navigate(['/login'], {
          queryParams: { reason: 'expired', returnUrl: router.url },
        });
      }
      return throwError(() => error);
    }),
  );
};
