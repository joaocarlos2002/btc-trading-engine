import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { formatTime } from '../../core/format/format';
import { IconComponent } from '../../shared/ui/icon.component';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { IndicatorsPanelComponent } from './indicators-panel.component';
import { LiveStore } from './live.store';
import { MarketHeaderComponent } from './market-header.component';
import { PerformancePanelComponent } from './performance-panel.component';
import { PositionPanelComponent } from './position-panel.component';
import { PriceChartComponent } from './price-chart.component';
import { SignalPanelComponent } from './signal-panel.component';
import { TradesTableComponent } from './trades-table.component';

/**
 * The live screen, ordered by how often each part is read: price, then the chart and the model's decision,
 * then the position and its results, then the indicators behind the decision and the trade history.
 */
@Component({
  selector: 'bte-live-page',
  imports: [
    PageHeaderComponent,
    IconComponent,
    MarketHeaderComponent,
    PriceChartComponent,
    SignalPanelComponent,
    PositionPanelComponent,
    PerformancePanelComponent,
    IndicatorsPanelComponent,
    TradesTableComponent,
  ],
  providers: [LiveStore],
  template: `
    <bte-page-header title="Ao vivo" [description]="description()" />

    @if (store.loadFailed()) {
      <div class="alert" role="alert" animate.enter="enter-fade-up">
        <bte-icon name="wifi-off" [size]="18" />
        <div class="alert-text">
          <p class="alert-title">Não foi possível carregar os dados</p>
          <p>O servidor não respondeu. O painel continua tentando pelo feed em tempo real.</p>
        </div>
        <button type="button" class="btn btn-sm" (click)="store.load()">
          <bte-icon name="rotate-ccw" [size]="14" /> Tentar de novo
        </button>
      </div>
    }

    <div class="layout stagger">
      <bte-market-header class="market" style="--stagger: 0" />
      <bte-price-chart class="chart" style="--stagger: 1" />
      <bte-signal-panel class="signal" style="--stagger: 2" />
      <bte-position-panel class="position" style="--stagger: 3" />
      <bte-performance-panel class="performance" style="--stagger: 4" />
      <bte-indicators-panel class="indicators" style="--stagger: 5" />
      <bte-trades-table class="trades" style="--stagger: 6" />
    </div>
  `,
  styles: `
    .layout {
      display: grid;
      grid-template-columns: repeat(12, minmax(0, 1fr));
      gap: var(--space-5);
    }

    .market,
    .indicators,
    .trades {
      grid-column: 1 / -1;
    }

    .chart {
      grid-column: span 8;
    }

    .signal {
      grid-column: span 4;
    }

    .position {
      grid-column: span 5;
    }

    .performance {
      grid-column: span 7;
    }

    .alert {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      margin-bottom: var(--space-5);
      padding: var(--space-3) var(--space-4);
      border: 1px solid color-mix(in srgb, var(--negative) 30%, transparent);
      border-radius: var(--radius-md);
      background: var(--negative-soft);
      color: var(--negative);
    }

    .alert-text {
      flex: 1;
      font-size: var(--text-sm);
      color: var(--text-muted);
    }

    .alert-title {
      font-weight: var(--weight-semibold);
      color: var(--text);
    }

    @media (max-width: 1279px) {
      .chart,
      .performance {
        grid-column: 1 / -1;
      }

      .signal,
      .position {
        grid-column: span 6;
      }
    }

    @media (max-width: 767px) {
      .layout {
        gap: var(--space-4);
      }

      .signal,
      .position {
        grid-column: 1 / -1;
      }

      .alert {
        flex-wrap: wrap;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LivePage {
  protected readonly store = inject(LiveStore);

  protected readonly description = computed(() => {
    const update = this.store.lastUpdate();
    const interval = this.store.intervalLabel();
    const base = interval ? `${this.store.instrument()} · velas de ${interval}` : this.store.instrument();
    return update ? `${base} · atualizado às ${formatTime(update)}` : base;
  });
}
