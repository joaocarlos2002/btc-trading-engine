import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { catchError, defer, finalize, map, Observable, of, switchMap, throwError } from 'rxjs';
import { Session } from '../models/auth.models';

/**
 * The login of issue #98, driven from Angular (issue #134). The session lives in the server's cookie;
 * this only mirrors who is logged in, so screens can adapt to the roles (a VIEWER cannot place orders).
 *
 * Every POST carries the XSRF-TOKEN cookie back in X-XSRF-TOKEN; HttpClient does that on its own
 * (withXsrfConfiguration uses the same names Spring Security writes).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  readonly session = signal<Session | null>(null);
  readonly isTrader = computed(() => this.session()?.roles.includes('TRADER') ?? false);

  /** Asks the server who is logged in: null when nobody is. A 401 here also hands out the CSRF cookie. */
  refresh(): Observable<Session | null> {
    return this.http.get<Session>('/api/auth/me').pipe(
      map((session) => {
        this.session.set(session);
        return session;
      }),
      catchError((error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 401) {
          this.session.set(null);
          return of(null);
        }
        return throwError(() => error);
      }),
    );
  }

  /** The known session, or the server's answer when none is known yet. */
  ensureSession(): Observable<Session | null> {
    const known = this.session();
    return known ? of(known) : this.refresh();
  }

  /** Resolves with the new session; errors with the HttpErrorResponse (401 = wrong credentials). */
  login(username: string, password: string): Observable<Session | null> {
    const body = new HttpParams().set('username', username).set('password', password);
    // Logging out drops the CSRF cookie, and the login POST needs one: fetch it first when missing.
    return defer(() => (hasXsrfCookie() ? of(null) : this.refresh())).pipe(
      switchMap(() => this.http.post<void>('/api/auth/login', body)),
      // The session id and the CSRF token change on login; /me brings both back.
      switchMap(() => this.refresh()),
    );
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', null).pipe(finalize(() => this.session.set(null)));
  }

  /** Forgets the session without calling the server, e.g. when a call came back 401. */
  expire(): void {
    this.session.set(null);
  }
}

function hasXsrfCookie(): boolean {
  return document.cookie.split(';').some((part) => part.trim().startsWith('XSRF-TOKEN='));
}
