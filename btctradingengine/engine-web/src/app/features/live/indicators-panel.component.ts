import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { formatTime } from '../../core/format/format';
import { CardComponent } from '../../shared/ui/card.component';
import { EmptyStateComponent, SkeletonComponent } from '../../shared/ui/feedback.components';
import { StatComponent } from '../../shared/ui/stat.component';
import { TabOption, TabsComponent } from '../../shared/ui/tabs.component';
import { FEATURE_GROUPS, flattenFeatures, readFeature } from './feature-catalog';
import { LiveStore } from './live.store';

const SELECTED_KEY = 'bte.indicators.group';

/**
 * Every indicator of the last closed candle, grouped by what it tells (trend, volatility, flow, price
 * action, derivatives, context) instead of one wall of 46 numbers. Each label explains itself on hover or focus.
 */
@Component({
  selector: 'bte-indicators-panel',
  imports: [CardComponent, TabsComponent, StatComponent, SkeletonComponent, EmptyStateComponent],
  template: `
    <bte-card heading="Indicadores" [subtitle]="subtitle()" icon="layers">
      <bte-tabs
        #tabs
        label="Grupos de indicadores"
        [options]="tabOptions"
        [(selected)]="selected"
        (selectedChange)="remember($event)"
      />

      <div
        class="panel"
        role="tabpanel"
        [id]="tabs.panelId(selected())"
        [attr.aria-labelledby]="tabs.tabId(selected())"
      >
        @if (readings(); as readings) {
          <p class="group-description">{{ group().description }}</p>
          @for (reading of readings; track reading.def.key) {
            <bte-stat
              class="indicator"
              [label]="reading.def.label"
              [value]="reading.value"
              [hint]="reading.def.hint"
              [note]="reading.note"
              [tip]="reading.def.tip"
              [tone]="reading.tone"
              size="sm"
            />
          }
        } @else if (store.loading()) {
          @for (placeholder of placeholders; track placeholder) {
            <div class="indicator placeholder" aria-hidden="true">
              <bte-skeleton width="60%" height="10px" />
              <bte-skeleton width="40%" height="16px" />
            </div>
          }
        } @else {
          <bte-empty-state
            class="empty"
            icon="layers"
            title="Aguardando a primeira vela fechada"
            description="Os indicadores são calculados no fechamento de cada vela."
          />
        }
      </div>
    </bte-card>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }

    .panel {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
      gap: 1px;
      margin-top: var(--space-4);
      border: 1px solid var(--border);
      border-radius: var(--radius-md);
      background: var(--surface);
      overflow: hidden;
    }

    /* Each cell draws the line to its right and below into the 1px gap; the outer ones are clipped */
    .group-description,
    .indicator,
    .empty {
      box-shadow:
        1px 0 0 var(--border),
        0 1px 0 var(--border);
    }

    .group-description {
      grid-column: 1 / -1;
      padding: var(--space-3) var(--space-4);
      background: var(--surface-2);
      font-size: var(--text-xs);
      color: var(--text-muted);
      animation: fade-in var(--dur-base) var(--ease-out);
    }

    .indicator {
      padding: var(--space-3) var(--space-4);
      background: var(--surface);
      animation: fade-in var(--dur-base) var(--ease-out);
    }

    .placeholder {
      display: grid;
      gap: var(--space-2);
    }

    .empty {
      grid-column: 1 / -1;
      background: var(--surface);
    }

    @media (max-width: 480px) {
      .panel {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IndicatorsPanelComponent {
  protected readonly store = inject(LiveStore);

  protected readonly tabOptions: TabOption[] = FEATURE_GROUPS.map((group) => ({ id: group.id, label: group.label }));
  protected readonly selected = signal(this.initialGroup());
  protected readonly placeholders = Array.from({ length: 8 }, (_, index) => index);

  protected readonly group = computed(
    () => FEATURE_GROUPS.find((group) => group.id === this.selected()) ?? FEATURE_GROUPS[0],
  );

  protected readonly readings = computed(() => {
    const features = this.store.features();
    if (!features) {
      return null;
    }
    const flat = flattenFeatures(features);
    return this.group().items.map((def) => readFeature(def, flat[def.key]));
  });

  protected readonly subtitle = computed(() => {
    const features = this.store.features();
    return features ? `Última vela fechada · calculados às ${formatTime(features.timestamp)}` : 'Última vela fechada';
  });

  protected remember(id: string): void {
    try {
      localStorage.setItem(SELECTED_KEY, id);
    } catch {
      // Storage blocked: the tab resets on reload
    }
  }

  private initialGroup(): string {
    try {
      const stored = localStorage.getItem(SELECTED_KEY);
      if (stored && FEATURE_GROUPS.some((group) => group.id === stored)) {
        return stored;
      }
    } catch {
      // Storage blocked
    }
    return FEATURE_GROUPS[0].id;
  }
}
