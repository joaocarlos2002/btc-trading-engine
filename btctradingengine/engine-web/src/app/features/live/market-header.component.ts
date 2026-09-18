import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { formatNumber, formatPercent, isNumber, toneOf } from '../../core/format/format';
import { SparklineComponent } from '../../shared/charts/sparkline.component';
import { ClockPipe, NumPipe } from '../../shared/pipes/format.pipes';
import { IconComponent } from '../../shared/ui/icon.component';
import { SkeletonComponent } from '../../shared/ui/feedback.components';
import { StatComponent } from '../../shared/ui/stat.component';
import { LiveStore } from './live.store';

/** The price at a glance: last trade, change since the last closed candle, that candle's range and the feed health. */
@Component({
  selector: 'bte-market-header',
  imports: [StatComponent, SparklineComponent, IconComponent, SkeletonComponent, NumPipe, ClockPipe],
  template: `
    <section class="market" aria-label="Preço atual">
      <div class="price-block">
        <p class="eyebrow">{{ pair() }} · Último preço</p>
        @if (store.price() !== null) {
          <p class="price num">
            {{ store.price() | num: 2 }}
            <span class="unit">USDT</span>
          </p>
          <p class="change" [class]="'change tone-' + change().tone">
            @if (change().tone !== 'neutral') {
              <bte-icon [name]="change().tone === 'positive' ? 'trending-up' : 'trending-down'" [size]="14" />
            }
            <span class="num">{{ change().points }} ({{ change().percent }})</span>
            <span class="since">desde o fechamento da última vela</span>
          </p>
        } @else {
          <bte-skeleton width="220px" height="40px" radius="var(--radius-sm)" />
          <bte-skeleton width="260px" height="14px" />
        }
      </div>

      <div class="spark" aria-hidden="true">
        <bte-sparkline [values]="store.tickPrices()" />
      </div>

      <div class="stats">
        <bte-stat
          label="Máxima"
          [value]="(candle()?.high | num: 2)"
          hint="última vela"
          tip="Preço máximo da última vela fechada. Rejeições nesse nível sugerem resistência."
          size="sm"
        />
        <bte-stat
          label="Mínima"
          [value]="(candle()?.low | num: 2)"
          hint="última vela"
          tip="Preço mínimo da última vela fechada. Rejeições nesse nível sugerem suporte."
          size="sm"
        />
        <bte-stat
          label="Volume"
          [value]="(candle()?.volume | num: 2)"
          hint="BTC · última vela"
          tip="Volume negociado na última vela fechada. Volume alto confirma movimentos."
          size="sm"
        />
        <bte-stat
          label="Último negócio"
          [value]="(store.tick()?.quantity | num: 5)"
          [hint]="'BTC às ' + (store.tick()?.eventTimestamp | clock)"
          tip="Quantidade e horário (na exchange) do último trade recebido da Binance."
          size="sm"
        />
        <bte-stat
          label="Latência"
          [value]="latency()"
          hint="exchange → servidor"
          tip="Tempo entre o evento na exchange e o recebimento no servidor. Quanto menor, melhor."
          [tone]="latencyTone()"
          size="sm"
        />
      </div>
    </section>
  `,
  styles: `
    .market {
      display: grid;
      grid-template-columns: minmax(0, auto) minmax(120px, 1fr);
      grid-template-areas:
        'price spark'
        'stats stats';
      gap: var(--space-5) var(--space-6);
      padding: var(--space-5) var(--space-6);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      background: var(--surface);
      box-shadow: var(--shadow-sm);
    }

    .price-block {
      grid-area: price;
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      min-width: 0;
    }

    .price {
      display: flex;
      align-items: baseline;
      gap: var(--space-2);
      font-size: var(--text-3xl);
      font-weight: var(--weight-semibold);
      line-height: 1.1;
      letter-spacing: -0.03em;
    }

    .unit {
      font-family: var(--font-sans);
      font-size: var(--text-sm);
      font-weight: var(--weight-medium);
      letter-spacing: 0;
      color: var(--text-subtle);
    }

    .change {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 6px;
      font-size: var(--text-sm);
      color: var(--text-muted);
    }

    .since {
      color: var(--text-subtle);
      font-size: var(--text-xs);
    }

    .spark {
      grid-area: spark;
      height: 72px;
      align-self: center;
    }

    .stats {
      grid-area: stats;
      display: grid;
      grid-template-columns: repeat(5, minmax(0, 1fr));
      gap: var(--space-4);
      padding-top: var(--space-4);
      border-top: 1px solid var(--border);
    }

    @media (max-width: 900px) {
      .stats {
        grid-template-columns: repeat(3, minmax(0, 1fr));
      }
    }

    @media (max-width: 560px) {
      .market {
        grid-template-columns: 1fr;
        grid-template-areas: 'price' 'spark' 'stats';
        padding: var(--space-4);
        gap: var(--space-4);
      }

      .price {
        font-size: var(--text-2xl);
      }

      .spark {
        height: 48px;
      }

      .stats {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MarketHeaderComponent {
  protected readonly store = inject(LiveStore);

  protected readonly candle = this.store.lastCandle;

  protected readonly pair = computed(() => {
    const instrument = this.store.instrument();
    return instrument.endsWith('USDT') ? `${instrument.slice(0, -4)} / USDT` : instrument;
  });

  protected readonly change = computed(() => {
    const price = this.store.price();
    const reference = this.candle()?.close;
    if (!isNumber(price) || !isNumber(reference) || reference === 0) {
      return { points: '—', percent: '—', tone: 'neutral' as const };
    }
    const diff = price - reference;
    return {
      points: formatNumber(diff, 2, true),
      percent: formatPercent((diff / reference) * 100, 2, { signed: true }),
      tone: toneOf(diff),
    };
  });

  protected readonly latency = computed(() => {
    const millis = this.store.latencyMs();
    return millis === null ? '—' : `${formatNumber(millis, 0)} ms`;
  });

  protected readonly latencyTone = computed(() => {
    const millis = this.store.latencyMs();
    return millis !== null && millis > 1500 ? ('negative' as const) : ('neutral' as const);
  });
}
