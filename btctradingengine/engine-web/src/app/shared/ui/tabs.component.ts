import { ChangeDetectionStrategy, Component, ElementRef, input, model, viewChildren } from '@angular/core';

export interface TabOption {
  id: string;
  label: string;
}

let nextId = 0;

/**
 * Segmented tabs (role=tablist). Arrow keys, Home and End move between tabs; the panel is the caller's,
 * labelled by `tabId(id)` and identified by `panelId(id)`.
 */
@Component({
  selector: 'bte-tabs',
  template: `
    <div class="list" role="tablist" [attr.aria-label]="label()">
      @for (option of options(); track option.id; let index = $index) {
        <button
          #tab
          type="button"
          role="tab"
          class="tab"
          [id]="tabId(option.id)"
          [attr.aria-selected]="option.id === selected()"
          [attr.aria-controls]="panelId(option.id)"
          [attr.tabindex]="option.id === selected() ? 0 : -1"
          (click)="selected.set(option.id)"
          (keydown)="onKey($event, index)"
        >
          {{ option.label }}
        </button>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
      overflow-x: auto;
      scrollbar-width: none;
    }

    :host::-webkit-scrollbar {
      display: none;
    }

    .list {
      display: inline-flex;
      gap: 2px;
      padding: 3px;
      border: 1px solid var(--border);
      border-radius: var(--radius-md);
      background: var(--surface-2);
    }

    .tab {
      height: 30px;
      padding: 0 var(--space-3);
      border-radius: calc(var(--radius-md) - 3px);
      color: var(--text-muted);
      font-size: var(--text-xs);
      font-weight: var(--weight-medium);
      white-space: nowrap;
      transition:
        background-color var(--dur-base) var(--ease-out),
        color var(--dur-base) var(--ease-out),
        box-shadow var(--dur-base) var(--ease-out);
    }

    .tab:hover {
      color: var(--text);
    }

    .tab[aria-selected='true'] {
      background: var(--surface);
      color: var(--text);
      box-shadow: var(--shadow-sm), 0 0 0 1px var(--border);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TabsComponent {
  readonly options = input.required<TabOption[]>();
  readonly label = input.required<string>();
  readonly selected = model.required<string>();

  private readonly prefix = `tabs-${nextId++}`;
  private readonly tabs = viewChildren<ElementRef<HTMLButtonElement>>('tab');

  tabId(id: string): string {
    return `${this.prefix}-tab-${id}`;
  }

  panelId(id: string): string {
    return `${this.prefix}-panel-${id}`;
  }

  protected onKey(event: KeyboardEvent, index: number): void {
    const count = this.options().length;
    const target =
      event.key === 'ArrowRight' ? (index + 1) % count
      : event.key === 'ArrowLeft' ? (index - 1 + count) % count
      : event.key === 'Home' ? 0
      : event.key === 'End' ? count - 1
      : -1;
    if (target < 0) {
      return;
    }
    event.preventDefault();
    this.selected.set(this.options()[target].id);
    this.tabs()[target]?.nativeElement.focus();
  }
}
