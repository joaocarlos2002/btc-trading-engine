import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { IconComponent, IconName } from './icon.component';

/** Placeholder block with a soft shimmer while data loads. */
@Component({
  selector: 'bte-skeleton',
  template: '',
  host: {
    'aria-hidden': 'true',
    '[style.width]': 'width()',
    '[style.height]': 'height()',
    '[style.border-radius]': 'radius()',
  },
  styles: `
    :host {
      display: block;
      background: linear-gradient(
        90deg,
        var(--skeleton) 25%,
        var(--skeleton-shine) 50%,
        var(--skeleton) 75%
      );
      background-size: 200% 100%;
      animation: shimmer 1.6s linear infinite;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SkeletonComponent {
  readonly width = input('100%');
  readonly height = input('14px');
  readonly radius = input('var(--radius-xs)');
}

/** Indeterminate spinner; announce the work with a visible label next to it. */
@Component({
  selector: 'bte-spinner',
  template: `<span class="ring" [style.width.px]="size()" [style.height.px]="size()"></span>`,
  host: { 'aria-hidden': 'true' },
  styles: `
    :host {
      display: inline-flex;
    }

    .ring {
      border: 2px solid color-mix(in srgb, currentColor 22%, transparent);
      border-top-color: currentColor;
      border-radius: 50%;
      animation: spin 0.7s linear infinite;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SpinnerComponent {
  readonly size = input(16);
}

/** What to show when there is nothing yet, and what to do about it ([default slot] for an action). */
@Component({
  selector: 'bte-empty-state',
  imports: [IconComponent],
  template: `
    <span class="icon"><bte-icon [name]="icon()" [size]="20" /></span>
    <p class="title">{{ title() }}</p>
    @if (description()) {
      <p class="description">{{ description() }}</p>
    }
    <ng-content />
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--space-2);
      padding: var(--space-8) var(--space-4);
      text-align: center;
      animation: fade-in var(--dur-slow) var(--ease-out);
    }

    :host(.compact) {
      padding: var(--space-5) var(--space-4);
    }

    .icon {
      display: grid;
      place-items: center;
      width: 40px;
      height: 40px;
      margin-bottom: var(--space-1);
      border-radius: var(--radius-md);
      background: var(--surface-2);
      border: 1px solid var(--border);
      color: var(--text-subtle);
    }

    .title {
      font-size: var(--text-sm);
      font-weight: var(--weight-medium);
      color: var(--text);
    }

    .description {
      max-width: 360px;
      font-size: var(--text-xs);
      color: var(--text-subtle);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmptyStateComponent {
  readonly icon = input<IconName>('inbox');
  readonly title = input.required<string>();
  readonly description = input('');
}
