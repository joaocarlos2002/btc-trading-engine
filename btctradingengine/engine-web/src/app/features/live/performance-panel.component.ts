import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { formatNumber, formatPercent, toneOf } from '../../core/format/format';
import { EquityChartComponent } from '../../shared/charts/equity-chart.component';
import { CardComponent } from '../../shared/ui/card.component';
import { EmptyStateComponent, SkeletonComponent } from '../../shared/ui/feedback.components';
import { StatComponent } from '../../shared/ui/stat.component';
import { LiveStore } from './live.store';

/** How the session is going: trade count, hit rate, P&L, Sharpe, drawdown and the equity curve. */
@Component({
  selector: 'bte-performance-panel',
  imports: [CardComponent, StatComponent, EquityChartComponent, EmptyStateComponent, SkeletonComponent],
  template: `
    <bte-card heading="Desempenho" subtitle="Trades fechados desde o início do motor" icon="chart-line">
      <div class="kpis">
        <bte-stat label="Trades" [value]="kpis().trades" [hint]="kpis().wins" size="lg"
          tip="Trades fechados que contam para o desempenho; entradas que falharam não entram." />
        <bte-stat label="Taxa de acerto" [value]="kpis().winRate" hint="trades com lucro" size="lg" />
        <bte-stat label="P&L total" [value]="kpis().pnl" hint="pontos de preço" [tone]="kpis().pnlTone" size="lg"
          tip="Soma do P&L por unidade (pontos de preço), não em USDT." />
        <bte-stat label="Sharpe" [value]="kpis().sharpe" hint="por trade" size="lg"
          tip="Retorno médio por trade dividido pelo desvio-padrão. Precisa de ao menos 2 trades." />
        <bte-stat label="Drawdown máx." [value]="kpis().drawdown" hint="da curva de capital" size="lg"
          tip="Maior queda do pico da curva de capital, compondo o retorno de cada trade sobre o capital inicial." />
      </div>

      <div class="chart">
        @if (store.equity().length) {
          <bte-equity-chart [points]="store.equity()" />
        } @else if (store.loading()) {
          <bte-skeleton height="100%" radius="var(--radius-md)" />
        } @else {
          <bte-empty-state
            class="compact"
            icon="chart-line"
            title="Sem trades fechados"
            description="A curva de P&L acumulado aparece depois do primeiro trade."
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

    bte-card {
      height: 100%;
    }

    .kpis {
      display: grid;
      grid-template-columns: repeat(5, minmax(0, 1fr));
      gap: var(--space-4);
    }

    .chart {
      height: 200px;
      margin-top: var(--space-5);
    }

    @media (max-width: 1100px) {
      .kpis {
        grid-template-columns: repeat(3, minmax(0, 1fr));
      }
    }

    @media (max-width: 480px) {
      .kpis {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PerformancePanelComponent {
  protected readonly store = inject(LiveStore);

  protected readonly kpis = computed(() => {
    const stats = this.store.stats();
    const total = stats?.totalTrades ?? 0;
    const wins = stats?.winningTrades ?? 0;
    return {
      trades: stats ? formatNumber(total, 0) : '—',
      wins: stats ? `${formatNumber(wins, 0)} com lucro` : '',
      winRate: total > 0 ? formatPercent((wins / total) * 100, 1) : '—',
      pnl: formatNumber(stats?.totalPnl, 2, true),
      pnlTone: toneOf(stats?.totalPnl),
      sharpe: total >= 2 ? formatNumber(stats?.sharpe, 2) : '—',
      drawdown: stats ? formatPercent(stats.maxDrawdown, 2) : '—',
    };
  });
}
