import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Title, one-line description and page-level actions/meta ([default slot]) at the top of each page. */
@Component({
  selector: 'bte-page-header',
  template: `
    <div class="titles">
      <h1>{{ title() }}</h1>
      @if (description()) {
        <p>{{ description() }}</p>
      }
    </div>
    <div class="aside"><ng-content /></div>
  `,
  styles: `
    :host {
      display: flex;
      align-items: flex-end;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: var(--space-4);
      margin-bottom: var(--space-6);
    }

    h1 {
      font-size: var(--text-2xl);
      font-weight: var(--weight-semibold);
    }

    p {
      margin-top: var(--space-1);
      font-size: var(--text-sm);
      color: var(--text-muted);
    }

    .aside {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--space-2);
    }

    .aside:empty {
      display: none;
    }

    @media (max-width: 640px) {
      :host {
        margin-bottom: var(--space-5);
      }

      h1 {
        font-size: var(--text-xl);
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PageHeaderComponent {
  readonly title = input.required<string>();
  readonly description = input('');
}
