import {
  ChangeDetectionStrategy,
  Component,
  effect,
  ElementRef,
  inject,
  Injectable,
  signal,
  viewChild,
} from '@angular/core';
import { IconComponent, IconName } from './icon.component';

export interface ConfirmOptions {
  title: string;
  message: string;
  confirmLabel: string;
  cancelLabel?: string;
  /** Styles the confirm button: positive for opening, danger for closing or destroying */
  tone?: 'primary' | 'positive' | 'danger';
  icon?: IconName;
}

interface PendingConfirm extends ConfirmOptions {
  resolve: (confirmed: boolean) => void;
}

/** A modal yes/no question, e.g. before sending an order. Resolves false on cancel, Escape or backdrop. */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  readonly pending = signal<PendingConfirm | null>(null);

  ask(options: ConfirmOptions): Promise<boolean> {
    this.pending()?.resolve(false);
    return new Promise((resolve) => this.pending.set({ ...options, resolve }));
  }

  answer(confirmed: boolean): void {
    const current = this.pending();
    if (current) {
      this.pending.set(null);
      current.resolve(confirmed);
    }
  }
}

@Component({
  selector: 'bte-confirm-host',
  imports: [IconComponent],
  template: `
    @if (confirm.pending(); as request) {
      <dialog
        #dialog
        class="dialog"
        aria-labelledby="confirm-title"
        aria-describedby="confirm-message"
        animate.enter="dialog-enter"
        animate.leave="dialog-leave"
        (cancel)="$event.preventDefault(); confirm.answer(false)"
        (click)="onBackdrop($event)"
      >
        <div class="panel">
          <div class="head">
            <span class="icon" [class]="'icon tone-' + (request.tone ?? 'primary')">
              <bte-icon [name]="request.icon ?? 'info'" [size]="18" />
            </span>
            <div>
              <h2 id="confirm-title">{{ request.title }}</h2>
              <p id="confirm-message">{{ request.message }}</p>
            </div>
          </div>
          <div class="actions">
            <button type="button" class="btn" autofocus (click)="confirm.answer(false)">
              {{ request.cancelLabel ?? 'Cancelar' }}
            </button>
            <button
              type="button"
              class="btn"
              [class.btn-primary]="(request.tone ?? 'primary') === 'primary'"
              [class.btn-positive]="request.tone === 'positive'"
              [class.btn-danger]="request.tone === 'danger'"
              (click)="confirm.answer(true)"
            >
              {{ request.confirmLabel }}
            </button>
          </div>
        </div>
      </dialog>
    }
  `,
  styles: `
    .dialog {
      width: min(440px, calc(100vw - 32px));
      max-width: none;
      margin: auto;
      padding: 0;
      border: 1px solid var(--border-strong);
      border-radius: var(--radius-lg);
      background: var(--surface);
      color: var(--text);
      box-shadow: var(--shadow-lg);
    }

    .dialog::backdrop {
      background: var(--overlay);
      backdrop-filter: blur(2px);
      animation: fade-in var(--dur-base) var(--ease-out);
    }

    .dialog-enter {
      animation: scale-in var(--dur-base) var(--ease-out);
    }

    .dialog-leave {
      animation: scale-out var(--dur-fast) var(--ease-in) forwards;
    }

    .dialog-leave::backdrop {
      animation: fade-out var(--dur-fast) var(--ease-in) forwards;
    }

    .panel {
      padding: var(--space-6);
    }

    .head {
      display: flex;
      gap: var(--space-4);
    }

    .icon {
      display: grid;
      place-items: center;
      flex-shrink: 0;
      width: 38px;
      height: 38px;
      border-radius: var(--radius-md);
      background: var(--accent-soft);
      color: var(--accent);
    }

    .icon.tone-positive {
      background: var(--positive-soft);
      color: var(--positive);
    }

    .icon.tone-danger {
      background: var(--negative-soft);
      color: var(--negative);
    }

    h2 {
      font-size: var(--text-md);
      margin-top: 2px;
    }

    p {
      margin-top: var(--space-2);
      font-size: var(--text-sm);
      color: var(--text-muted);
    }

    .actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-2);
      margin-top: var(--space-6);
    }

    @media (max-width: 480px) {
      .actions {
        flex-direction: column-reverse;
      }

      .actions .btn {
        width: 100%;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmHostComponent {
  protected readonly confirm = inject(ConfirmService);
  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    // showModal gives the focus trap, Escape and the top layer for free
    effect(() => {
      const element = this.dialog()?.nativeElement;
      if (element && !element.open) {
        element.showModal();
      }
    });
  }

  /** A click on the dialog element itself (not its panel) is a click on the backdrop. */
  protected onBackdrop(event: MouseEvent): void {
    if (event.target === this.dialog()?.nativeElement) {
      this.confirm.answer(false);
    }
  }
}
