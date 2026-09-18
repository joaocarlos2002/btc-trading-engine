import { inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  defer,
  filter,
  map,
  Observable,
  repeat,
  retry,
  share,
  timer,
} from 'rxjs';
import { webSocket, WebSocketSubjectConfig } from 'rxjs/webSocket';
import { AuthService } from '../auth/auth.service';
import { LiveData, LiveMessage, LiveMessageType } from '../models/live.models';

export type ConnectionStatus = 'idle' | 'connecting' | 'open' | 'reconnecting';

/** Reconnect backoff: 1 s, 2 s, 4 s ... capped at 15 s. */
export function reconnectDelay(attempt: number): number {
  return Math.min(1000 * 2 ** Math.max(0, attempt - 1), 15_000);
}

/**
 * The /ws/live feed (DashboardState broadcasts {type, data} at most every 50 ms per type).
 *
 * One socket is shared by every subscriber and closed when the last one leaves, so the connection only
 * exists while a live screen is open. A drop or a clean close reconnects with backoff, without a page
 * reload; after a few failed attempts the session is checked, since an expired login also fails the
 * handshake; without a session the user is sent back to the login.
 */
@Injectable({ providedIn: 'root' })
export class LiveSocket {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly status = signal<ConnectionStatus>('idle');
  readonly attempt = signal(0);

  readonly messages$: Observable<LiveMessage> = defer(() => {
    this.status.set(this.attempt() === 0 ? 'connecting' : 'reconnecting');
    return webSocket<LiveMessage>(this.config());
  }).pipe(
    retry({ delay: (_error, count) => this.scheduleReconnect(count) }),
    repeat({ delay: () => this.scheduleReconnect(this.attempt() + 1) }),
    share({ resetOnRefCountZero: () => this.onIdle() }),
  );

  /** The payloads of one message type. */
  channel<T extends LiveMessageType>(type: T): Observable<LiveData<T>> {
    return this.messages$.pipe(
      filter((message): message is Extract<LiveMessage, { type: T }> => message.type === type),
      map((message) => message.data as LiveData<T>),
    );
  }

  private config(): WebSocketSubjectConfig<LiveMessage> {
    const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
    return {
      url: `${protocol}://${location.host}/ws/live`,
      openObserver: {
        next: () => {
          this.attempt.set(0);
          this.status.set('open');
        },
      },
      closeObserver: {
        next: () => {
          if (this.status() !== 'idle') {
            this.status.set('reconnecting');
          }
        },
      },
    };
  }

  private scheduleReconnect(attempt: number): Observable<number> {
    this.attempt.set(attempt);
    this.status.set('reconnecting');
    if (attempt % 3 === 0) {
      this.auth.refresh().subscribe({
        next: (session) => {
          if (!session) {
            void this.router.navigate(['/login'], {
              queryParams: { reason: 'expired', returnUrl: this.router.url },
            });
          }
        },
        // Server unreachable: keep retrying the socket
        error: () => undefined,
      });
    }
    return timer(reconnectDelay(attempt));
  }

  private onIdle(): Observable<number> {
    this.status.set('idle');
    this.attempt.set(0);
    return timer(0);
  }
}
