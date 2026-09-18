import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { IconComponent, IconName } from './icon.component';

let nextId = 0;

/**
 * The surface every section sits on. Header with title, optional subtitle and actions
 * ([card-actions] slot); body is the default slot.
 */
@Component({
  selector: 'bte-card',
  imports: [IconComponent],
  template: `
    @if (heading()) {
      <header class="card-header">
        <div class="card-title">
          @if (icon(); as icon) {
            <span class="card-icon"><bte-icon [name]="icon" [size]="16" /></span>
          }
          <div class="card-titles">
            <h2 [id]="headingId">{{ heading() }}</h2>
            @if (subtitle()) {
              <p class="card-subtitle">{{ subtitle() }}</p>
            }
          </div>
        </div>
        <div class="card-actions"><ng-content select="[card-actions]" /></div>
      </header>
    }
    <div class="card-body" [class.flush]="flush()">
      <ng-content />
    </div>
  `,
  host: {
    '[attr.role]': "heading() ? 'region' : null",
    '[attr.aria-labelledby]': 'heading() ? headingId : null',
  },
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      min-width: 0;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      box-shadow: var(--shadow-sm);
      transition:
        border-color var(--dur-base) var(--ease-out),
        background-color var(--dur-base) var(--ease-out);
    }

    .card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3);
      min-height: 56px;
      padding: var(--space-4) var(--space-5) 0;
    }

    .card-title {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      min-width: 0;
    }

    .card-icon {
      display: grid;
      place-items: center;
      width: 30px;
      height: 30px;
      border-radius: var(--radius-sm);
      background: var(--surface-2);
      border: 1px solid var(--border);
      color: var(--text-muted);
    }

    .card-titles {
      min-width: 0;
    }

    h2 {
      font-size: var(--text-base);
      font-weight: var(--weight-semibold);
      letter-spacing: -0.01em;
    }

    .card-subtitle {
      margin-top: 2px;
      font-size: var(--text-xs);
      color: var(--text-subtle);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .card-actions {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      flex-shrink: 0;
    }

    .card-actions:empty {
      display: none;
    }

    .card-body {
      flex: 1;
      padding: var(--space-4) var(--space-5) var(--space-5);
      min-width: 0;
    }

    .card-body.flush {
      padding: var(--space-3) 0 0;
    }

    @media (max-width: 640px) {
      .card-header {
        padding: var(--space-4) var(--space-4) 0;
      }

      .card-body {
        padding: var(--space-4);
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CardComponent {
  readonly heading = input('');
  readonly subtitle = input('');
  readonly icon = input<IconName | null>(null);
  /** No body padding: for tables and charts that run edge to edge */
  readonly flush = input(false);

  protected readonly headingId = `card-title-${nextId++}`;
}
