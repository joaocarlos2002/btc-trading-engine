import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
} from '@angular/core';
import { LiveSocket } from '../core/live/live-socket.service';
import { BadgeComponent, BadgeTone } from '../shared/ui/badge.component';

/** State of the live feed; hidden while no screen uses it. */
@Component({
  selector: 'bte-connection-status',
  imports: [BadgeComponent],
  template: `
    <span class="sr-only" aria-live="polite">{{ visible() ? announcement() : '' }}</span>
    @if (visible()) {
      <bte-badge
        [tone]="view().tone"
        [dot]="true"
        [pulse]="socket.status() === 'open'"
        size="md"
        animate.enter="enter-fade"
      >
        {{ view().label }}
      </bte-badge>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConnectionStatusComponent {
  protected readonly socket = inject(LiveSocket);

  protected readonly visible = computed(() => this.socket.status() !== 'idle');

  protected readonly view = computed<{ label: string; tone: BadgeTone }>(() => {
    switch (this.socket.status()) {
      case 'open':
        return { label: 'Tempo real', tone: 'positive' };
      case 'connecting':
        return { label: 'Conectando…', tone: 'warning' };
      case 'reconnecting':
        return { label: 'Reconectando…', tone: 'warning' };
      default:
        return { label: 'Desconectado', tone: 'neutral' };
    }
  });

  protected readonly announcement = computed(() =>
    this.socket.status() === 'open'
      ? 'Conectado ao feed em tempo real'
      : `Feed em tempo real: ${this.view().label}`,
  );
}

/** HH:mm:ss UTC, the time base of the exchange and the candles. */
@Component({
  selector: 'bte-utc-clock',
  template: `<time class="num" [attr.datetime]="iso()">{{ time() }} <span>UTC</span></time>`,
  styles: `
    time {
      font-size: var(--text-xs);
      color: var(--text-muted);
      white-space: nowrap;
    }

    span {
      color: var(--text-subtle);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UtcClockComponent {
  private readonly now = signal(new Date());
  protected readonly iso = computed(() => this.now().toISOString());
  protected readonly time = computed(() => this.iso().slice(11, 19));

  constructor() {
    const timer = setInterval(() => this.now.set(new Date()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }
}
