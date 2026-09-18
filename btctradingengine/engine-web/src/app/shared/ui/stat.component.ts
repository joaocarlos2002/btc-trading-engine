import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Tone } from '../../core/format/format';
import { TooltipDirective } from './tooltip.directive';

/**
 * A labeled value: the building block of KPI rows and indicator grids. `tip` explains the metric
 * (tooltip on the label, reachable by keyboard); `note` reads the value in words, so its meaning never
 * depends on color alone.
 */
@Component({
  selector: 'bte-stat',
  imports: [TooltipDirective],
  template: `
    <div class="term">
      @if (tip()) {
        <span class="label has-tip" tabindex="0" [bteTooltip]="tip()">{{ label() }}</span>
      } @else {
        <span class="label">{{ label() }}</span>
      }
    </div>
    <div class="desc">
      <span class="value num" [class]="'tone-' + tone()">{{ value() }}</span>
      @if (note() || hint()) {
        <span class="meta">
          @if (note()) {
            <span class="note">{{ note() }}</span>
          }
          @if (hint()) {
            <span class="hint">{{ hint() }}</span>
          }
        </span>
      }
    </div>
  `,
  host: { '[class]': "'size-' + size()" },
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 0;
    }

    .term {
      display: flex;
      min-width: 0;
    }

    .label {
      font-size: var(--text-xs);
      color: var(--text-subtle);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .desc {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
    }

    .value {
      font-size: var(--text-md);
      font-weight: var(--weight-medium);
      color: var(--text);
      white-space: nowrap;
      transition: color var(--dur-base) var(--ease-out);
    }

    .tone-positive {
      color: var(--positive);
    }

    .tone-negative {
      color: var(--negative);
    }

    .meta {
      display: flex;
      flex-wrap: wrap;
      gap: 0 var(--space-2);
      font-size: var(--text-2xs);
    }

    .note {
      color: var(--text-muted);
      font-weight: var(--weight-medium);
    }

    .hint {
      color: var(--text-subtle);
    }

    :host(.size-sm) .value {
      font-size: var(--text-sm);
    }

    :host(.size-lg) .value {
      font-size: var(--text-xl);
      font-weight: var(--weight-semibold);
      letter-spacing: var(--tracking-tight);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatComponent {
  readonly label = input.required<string>();
  readonly value = input.required<string>();
  readonly hint = input('');
  readonly note = input('');
  readonly tip = input('');
  readonly tone = input<Tone>('neutral');
  readonly size = input<'sm' | 'md' | 'lg'>('md');
}
