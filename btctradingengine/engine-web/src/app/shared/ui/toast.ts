import { ChangeDetectionStrategy, Component, inject, Injectable, signal } from '@angular/core';
import { IconComponent, IconName } from './icon.component';

export type ToastTone = 'success' | 'error' | 'warning' | 'info';

export interface Toast {
  id: number;
  tone: ToastTone;
  title: string;
  message?: string;
}

const DURATION: Record<ToastTone, number> = { success: 4000, info: 4000, warning: 6000, error: 8000 };
const MAX_VISIBLE = 4;

/** Short-lived feedback on the result of an action. Errors stay longer and are announced assertively. */
@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly toasts = signal<Toast[]>([]);
  private nextId = 0;
  private readonly timers = new Map<number, ReturnType<typeof setTimeout>>();

  show(tone: ToastTone, title: string, message?: string): void {
    const toast: Toast = { id: this.nextId++, tone, title, message };
    this.toasts.update((list) => [...list, toast].slice(-MAX_VISIBLE));
    this.schedule(toast.id, DURATION[tone]);
  }

  success(title: string, message?: string): void {
    this.show('success', title, message);
  }

  error(title: string, message?: string): void {
    this.show('error', title, message);
  }

  warning(title: string, message?: string): void {
    this.show('warning', title, message);
  }

  info(title: string, message?: string): void {
    this.show('info', title, message);
  }

  dismiss(id: number): void {
    clearTimeout(this.timers.get(id));
    this.timers.delete(id);
    this.toasts.update((list) => list.filter((toast) => toast.id !== id));
  }

  /** Hovering a toast keeps it on screen until the pointer leaves. */
  hold(id: number): void {
    clearTimeout(this.timers.get(id));
  }

  release(id: number): void {
    this.schedule(id, 2500);
  }

  private schedule(id: number, delay: number): void {
    clearTimeout(this.timers.get(id));
    this.timers.set(id, setTimeout(() => this.dismiss(id), delay));
  }
}

const ICON: Record<ToastTone, IconName> = {
  success: 'check-circle',
  error: 'alert-circle',
  warning: 'alert-triangle',
  info: 'info',
};

@Component({
  selector: 'bte-toast-host',
  imports: [IconComponent],
  template: `
    <div class="region" role="status" aria-live="polite">
      @for (toast of toasts.toasts(); track toast.id) {
        <div
          class="toast"
          [class]="'toast tone-' + toast.tone"
          [attr.role]="toast.tone === 'error' ? 'alert' : null"
          animate.enter="enter-slide"
          animate.leave="leave-slide"
          (mouseenter)="toasts.hold(toast.id)"
          (mouseleave)="toasts.release(toast.id)"
        >
          <span class="icon"><bte-icon [name]="icon[toast.tone]" [size]="18" /></span>
          <div class="text">
            <p class="title">{{ toast.title }}</p>
            @if (toast.message) {
              <p class="message">{{ toast.message }}</p>
            }
          </div>
          <button
            type="button"
            class="btn btn-ghost btn-icon btn-sm close"
            aria-label="Fechar notificação"
            (click)="toasts.dismiss(toast.id)"
          >
            <bte-icon name="x" [size]="14" />
          </button>
        </div>
      }
    </div>
  `,
  styles: `
    .region {
      position: fixed;
      right: var(--space-5);
      bottom: var(--space-5);
      z-index: var(--z-toast);
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      width: min(380px, calc(100vw - 32px));
      pointer-events: none;
    }

    .toast {
      display: flex;
      align-items: flex-start;
      gap: var(--space-3);
      padding: var(--space-3) var(--space-3) var(--space-3) var(--space-4);
      border: 1px solid var(--border-strong);
      border-radius: var(--radius-md);
      background: var(--surface);
      box-shadow: var(--shadow-lg);
      pointer-events: auto;
    }

    .icon {
      margin-top: 1px;
      color: var(--text-muted);
    }

    .tone-success .icon {
      color: var(--positive);
    }

    .tone-error .icon {
      color: var(--negative);
    }

    .tone-warning .icon {
      color: var(--warning);
    }

    .tone-info .icon {
      color: var(--info);
    }

    .text {
      flex: 1;
      min-width: 0;
      padding-top: 1px;
    }

    .title {
      font-size: var(--text-sm);
      font-weight: var(--weight-semibold);
    }

    .message {
      margin-top: 2px;
      font-size: var(--text-xs);
      color: var(--text-muted);
      overflow-wrap: anywhere;
    }

    .close {
      margin: -2px -2px 0 0;
    }

    @media (max-width: 640px) {
      .region {
        right: 16px;
        left: 16px;
        bottom: 16px;
        width: auto;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ToastHostComponent {
  protected readonly toasts = inject(ToastService);
  protected readonly icon = ICON;
}
