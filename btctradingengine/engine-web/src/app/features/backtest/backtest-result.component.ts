import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { formatDate, formatDecimal, formatPercent, toneOf } from '../../core/format/format';
import { NumPipe } from '../../shared/pipes/format.pipes';
import { BadgeComponent } from '../../shared/ui/badge.component';
import { CardComponent } from '../../shared/ui/card.component';
import { StatComponent } from '../../shared/ui/stat.component';
import { RunRecord } from './backtest.store';
import { ALL_PARAMS } from './param-catalog';

interface ParamChip {
  key: string;
  label: string;
  value: string;
  overridden: boolean;
}

/** The report of one run: headline results first, then the detail, then exactly which parameters it used. */
@Component({
  selector: 'bte-backtest-result',
  imports: [CardComponent, StatComponent, BadgeComponent, NumPipe],
  template: `
    @let result = run().result;
    @let report = result.report;
    <bte-card heading="Resultado" [subtitle]="subtitle()" icon="chart-line">
      <div card-actions class="meta">
        @if (overrideCount()) {
          <bte-badge tone="accent">{{ overrideCount() }} {{ overrideCount() === 1 ? 'ajuste' : 'ajustes' }}</bte-badge>
        } @else {
          <bte-badge>Config padrão</bte-badge>
        }
      </div>

      <div class="headline">
        <bte-stat label="Retorno" [value]="returnText()" [tone]="returnTone()" hint="sobre o capital inicial" size="lg" />
        <bte-stat label="P&L total" [value]="report.totalPnL | num: 2 : true" [tone]="pnlTone()" hint="USDT" size="lg" />
        <bte-stat
          label="Profit factor"
          [value]="report.profitFactor | num: 2"
          [tone]="report.profitFactor >= 1 ? 'positive' : 'negative'"
          [note]="report.profitFactor >= 1 ? 'Lucrativo' : 'Perdedor'"
          tip="Lucro bruto ÷ prejuízo bruto. Acima de 1, a estratégia ganhou mais do que perdeu."
          size="lg"
        />
        <bte-stat label="Taxa de acerto" [value]="winRate()" [hint]="report.winTrades + ' de ' + report.totalTrades + ' trades'" size="lg" />
      </div>

      <div class="details">
        <bte-stat label="Trades" [value]="report.totalTrades | num: 0" size="sm" />
        <bte-stat label="Ganhos / perdas" [value]="report.winTrades + ' / ' + report.loseTrades" size="sm" />
        <bte-stat label="Retorno médio" [value]="averageReturn()" hint="por trade" [tone]="averageTone()" size="sm" />
        <bte-stat label="Patrimônio final" [value]="report.finalEquity | num: 2" hint="USDT" size="sm" />
        <bte-stat label="Drawdown máx." [value]="drawdown()" size="sm"
          tip="Maior queda do pico da curva de capital durante o período." />
        <bte-stat label="Sharpe" [value]="report.sharpeRatio | num: 3" size="sm"
          tip="Retorno médio por trade dividido pelo desvio-padrão dos retornos." />
        <bte-stat label="Duração média" [value]="(report.averageBarsPerTrade | num: 1) + ' velas'" hint="por trade" size="sm" />
        <bte-stat label="Velas analisadas" [value]="result.candleCount | num: 0" [hint]="result.interval" size="sm" />
      </div>

      <div class="data-notes">
        <bte-badge [tone]="result.derivativesLoaded ? 'positive' : 'neutral'" [dot]="true">
          {{ result.derivativesLoaded ? 'Com dados de derivativos' : 'Sem dados de derivativos' }}
        </bte-badge>
        @if (result.sizeSplitCandles > 0) {
          <bte-badge tone="info" [dot]="true">Tamanho dos trades em {{ result.sizeSplitCandles | num: 0 }} velas</bte-badge>
        }
      </div>

      <details class="params">
        <summary>Parâmetros usados</summary>
        <ul class="chips">
          @for (chip of chips(); track chip.key) {
            <li class="chip" [class.overridden]="chip.overridden">
              <span class="chip-label">{{ chip.label }}</span>
              <span class="num">{{ chip.value }}</span>
            </li>
          }
        </ul>
      </details>
    </bte-card>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }

    .headline {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: var(--space-4);
      padding-bottom: var(--space-5);
      border-bottom: 1px solid var(--border);
    }

    .details {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: var(--space-4) var(--space-5);
      padding: var(--space-5) 0;
    }

    .data-notes {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
    }

    .params {
      margin-top: var(--space-4);
      padding-top: var(--space-4);
      border-top: 1px solid var(--border);
    }

    .params summary {
      width: fit-content;
      font-size: var(--text-sm);
      font-weight: var(--weight-medium);
      color: var(--text-muted);
      cursor: pointer;
      border-radius: var(--radius-xs);
    }

    .params summary:hover {
      color: var(--text);
    }

    .params[open] .chips {
      animation: fade-up var(--dur-base) var(--ease-out);
    }

    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      margin: var(--space-3) 0 0;
      padding: 0;
      list-style: none;
    }

    .chip {
      display: inline-flex;
      gap: 6px;
      padding: 3px 8px;
      border: 1px solid var(--border);
      border-radius: var(--radius-sm);
      background: var(--surface-2);
      font-size: var(--text-xs);
    }

    .chip-label {
      color: var(--text-subtle);
    }

    .chip.overridden {
      border-color: color-mix(in srgb, var(--accent) 40%, transparent);
      background: var(--accent-soft);
    }

    .chip.overridden .chip-label {
      color: var(--accent);
    }

    @media (max-width: 720px) {
      .headline,
      .details {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BacktestResultComponent {
  readonly run = input.required<RunRecord>();

  private readonly report = computed(() => this.run().result.report);

  protected readonly subtitle = computed(() => {
    const result = this.run().result;
    return `${result.symbol} · ${result.interval} · ${formatDate(result.rangeStart)} → ${formatDate(result.rangeEnd)}`;
  });
  protected readonly overrideCount = computed(() => Object.keys(this.run().overrides).length);
  protected readonly returnText = computed(() => formatPercent(this.report().returnPercent, 2, { signed: true }));
  protected readonly returnTone = computed(() => toneOf(this.report().returnPercent));
  protected readonly pnlTone = computed(() => toneOf(this.report().totalPnL));
  protected readonly winRate = computed(() => formatPercent(this.report().winRate, 1));
  protected readonly averageReturn = computed(() =>
    formatPercent(this.report().averageReturnPercent, 3, { signed: true }),
  );
  protected readonly averageTone = computed(() => toneOf(this.report().averageReturnPercent));
  protected readonly drawdown = computed(() => formatPercent(this.report().maxDrawdown, 2));

  protected readonly chips = computed<ParamChip[]>(() => {
    const params = this.run().result.params;
    const overrides = this.run().overrides;
    return ALL_PARAMS.filter((def) => params[def.key] !== undefined).map((def) => {
      const value = params[def.key];
      return {
        key: def.key,
        label: def.label,
        value: typeof value === 'number' ? formatDecimal(value, def.integer ? 0 : 6) : String(value),
        overridden: def.key in overrides,
      };
    });
  });
}
