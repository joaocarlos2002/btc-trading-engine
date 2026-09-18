import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export type BadgeTone = 'neutral' | 'positive' | 'negative' | 'warning' | 'accent' | 'info';

/** Small status label. The text always carries the meaning; color only reinforces it. */
@Component({
  selector: 'bte-badge',
  template: `
    @if (dot()) {
      <span class="dot" [class.pulse]="pulse()" aria-hidden="true"></span>
    }
    <ng-content />
  `,
  host: {
    '[class]': "'tone-' + tone() + (size() === 'md' ? ' md' : '')",
  },
  styles: `
    :host {
      --badge-fg: var(--text-muted);
      --badge-bg: var(--surface-2);
      --badge-border: var(--border);

      display: inline-flex;
      align-items: center;
      gap: 6px;
      height: 22px;
      padding: 0 8px;
      border: 1px solid var(--badge-border);
      border-radius: var(--radius-full);
      background: var(--badge-bg);
      color: var(--badge-fg);
      font-size: var(--text-2xs);
      font-weight: var(--weight-semibold);
      letter-spacing: 0.02em;
      white-space: nowrap;
      transition:
        background-color var(--dur-base) var(--ease-out),
        color var(--dur-base) var(--ease-out),
        border-color var(--dur-base) var(--ease-out);
    }

    :host(.md) {
      height: 26px;
      padding: 0 10px;
      font-size: var(--text-xs);
    }

    :host(.tone-positive) {
      --badge-fg: var(--positive);
      --badge-bg: var(--positive-soft);
      --badge-border: transparent;
    }

    :host(.tone-negative) {
      --badge-fg: var(--negative);
      --badge-bg: var(--negative-soft);
      --badge-border: transparent;
    }

    :host(.tone-warning) {
      --badge-fg: var(--warning);
      --badge-bg: var(--warning-soft);
      --badge-border: transparent;
    }

    :host(.tone-accent) {
      --badge-fg: var(--accent);
      --badge-bg: var(--accent-soft);
      --badge-border: transparent;
    }

    :host(.tone-info) {
      --badge-fg: var(--info);
      --badge-bg: var(--info-soft);
      --badge-border: transparent;
    }

    .dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: currentColor;
      color: var(--badge-fg);
    }

    .dot.pulse {
      animation: pulse-dot 2s var(--ease-out) infinite;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BadgeComponent {
  readonly tone = input<BadgeTone>('neutral');
  readonly dot = input(false);
  readonly pulse = input(false);
  readonly size = input<'sm' | 'md'>('sm');
}
