import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import {
  CandlestickData,
  CandlestickSeries,
  ColorType,
  createChart,
  CrosshairMode,
  HistogramData,
  HistogramSeries,
  IChartApi,
  IPriceLine,
  ISeriesApi,
  LineSeries,
  LineStyle,
  MouseEventParams,
  Time,
  UTCTimestamp,
} from 'lightweight-charts';
import { formatCompact, formatNumber, formatShortTime } from '../../core/format/format';
import { Candle } from '../../core/models/live.models';
import { ThemeService } from '../../core/theme/theme.service';
import { cssVar, frameScheduler, withAlpha } from '../../shared/charts/chart-support';
import { CardComponent } from '../../shared/ui/card.component';
import { EmptyStateComponent, SkeletonComponent } from '../../shared/ui/feedback.components';
import { LiveStore } from './live.store';

const SMA_PERIOD = 20;

interface Legend {
  time: string;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: string;
  rising: boolean;
}

function toTime(iso: string): UTCTimestamp {
  return Math.floor(Date.parse(iso) / 1000) as UTCTimestamp;
}

/** Simple moving average of the closes; undefined until the window is full. */
export function movingAverage(values: number[], period: number): (number | undefined)[] {
  let sum = 0;
  return values.map((value, index) => {
    sum += value;
    if (index >= period) {
      sum -= values[index - period];
    }
    return index >= period - 1 ? sum / period : undefined;
  });
}

/**
 * 15-minute candles (Lightweight Charts), volume underneath, SMA 20 and a dashed line at the live price.
 * The chart lives outside Angular's templates: it is redrawn at most once per frame, and a new closed
 * candle is appended instead of redrawing the whole series.
 */
@Component({
  selector: 'bte-price-chart',
  imports: [CardComponent, SkeletonComponent, EmptyStateComponent],
  template: `
    <bte-card heading="Gráfico" [subtitle]="subtitle()" icon="chart-line" [flush]="true">
      <div card-actions class="legend-key" aria-hidden="true">
        <span><i class="swatch sma"></i>SMA {{ smaPeriod }}</span>
        <span><i class="swatch live"></i>Preço atual</span>
      </div>
      <div class="chart-wrap">
        @if (legend(); as legend) {
          <div class="legend num" aria-hidden="true">
            <span class="legend-time">{{ legend.time }}</span>
            <span>Abe <b>{{ legend.open }}</b></span>
            <span>Máx <b>{{ legend.high }}</b></span>
            <span>Mín <b>{{ legend.low }}</b></span>
            <span>Fec <b [class.up]="legend.rising" [class.down]="!legend.rising">{{ legend.close }}</b></span>
            <span>Vol <b>{{ legend.volume }}</b></span>
          </div>
        }
        <div
          #container
          class="chart"
          role="img"
          [attr.aria-label]="description()"
        ></div>
        @if (store.loading() && !store.candles().length) {
          <div class="overlay"><bte-skeleton height="100%" radius="var(--radius-md)" /></div>
        } @else if (!store.candles().length) {
          <div class="overlay">
            <bte-empty-state
              icon="chart-line"
              title="Aguardando velas"
              description="O histórico aparece assim que o motor fechar a primeira vela."
            />
          </div>
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

    .legend-key {
      display: flex;
      gap: var(--space-4);
      font-size: var(--text-xs);
      color: var(--text-subtle);
    }

    .legend-key span {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }

    .swatch {
      display: inline-block;
      width: 14px;
      height: 2px;
      border-radius: 2px;
      background: var(--info);
    }

    .swatch.live {
      background: repeating-linear-gradient(90deg, var(--accent) 0 4px, transparent 4px 7px);
    }

    .chart-wrap {
      position: relative;
      height: 380px;
      padding: 0 var(--space-2) var(--space-2);
    }

    .chart {
      width: 100%;
      height: 100%;
    }

    .legend {
      position: absolute;
      top: var(--space-2);
      left: var(--space-5);
      z-index: 2;
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-1) var(--space-3);
      padding: var(--space-1) var(--space-2);
      border-radius: var(--radius-sm);
      background: color-mix(in srgb, var(--surface) 85%, transparent);
      font-size: var(--text-2xs);
      color: var(--text-subtle);
      pointer-events: none;
      animation: fade-in var(--dur-fast) var(--ease-out);
    }

    .legend b {
      font-weight: var(--weight-medium);
      color: var(--text);
    }

    .legend b.up {
      color: var(--positive);
    }

    .legend b.down {
      color: var(--negative);
    }

    .legend-time {
      color: var(--text-muted);
    }

    .overlay {
      position: absolute;
      inset: 0 var(--space-4) var(--space-4);
      z-index: 3;
      display: grid;
      place-items: center;
      background: var(--surface);
    }

    .overlay bte-skeleton {
      width: 100%;
    }

    @media (max-width: 640px) {
      .chart-wrap {
        height: 300px;
      }

      .legend-key {
        display: none;
      }

      .legend {
        left: var(--space-3);
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PriceChartComponent {
  protected readonly store = inject(LiveStore);
  private readonly theme = inject(ThemeService);
  private readonly container = viewChild.required<ElementRef<HTMLElement>>('container');

  protected readonly smaPeriod = SMA_PERIOD;
  protected readonly legend = signal<Legend | null>(null);

  private chart: IChartApi | null = null;
  private candleSeries: ISeriesApi<'Candlestick'> | null = null;
  private volumeSeries: ISeriesApi<'Histogram'> | null = null;
  private smaSeries: ISeriesApi<'Line'> | null = null;
  private livePrice: IPriceLine | null = null;
  /** What the series currently hold, to append instead of redrawing when only a candle was added */
  private drawn: Candle[] = [];
  private readonly redraw = frameScheduler(() => this.drawCandles());
  private readonly repaintPrice = frameScheduler(() => this.drawLivePrice());

  constructor() {
    afterNextRender(() => this.create());
    effect(() => {
      this.store.candles();
      this.redraw.schedule();
    });
    effect(() => {
      this.store.price();
      this.repaintPrice.schedule();
    });
    effect(() => {
      this.theme.theme();
      // Only the theme triggers this; applyTheme reads the candles without subscribing to them
      untracked(() => {
        if (this.chart) {
          this.applyTheme();
        }
      });
    });
    inject(DestroyRef).onDestroy(() => {
      this.redraw.cancel();
      this.repaintPrice.cancel();
      this.chart?.remove();
    });
  }

  protected description(): string {
    const last = this.store.lastCandle();
    return last
      ? `Gráfico de velas; a última fechou em ${formatNumber(last.close)} USDT às ${formatShortTime(last.closeTime)}`
      : 'Gráfico de velas, sem dados ainda';
  }

  protected subtitle(): string {
    const interval = this.store.intervalLabel();
    return interval ? `Velas de ${interval} · horários em UTC` : 'Horários em UTC';
  }

  private create(): void {
    const chart = createChart(this.container().nativeElement, {
      autoSize: true,
      layout: { background: { type: ColorType.Solid, color: 'transparent' }, attributionLogo: true },
      crosshair: { mode: CrosshairMode.Normal },
      rightPriceScale: { borderVisible: false, scaleMargins: { top: 0.12, bottom: 0.24 } },
      timeScale: { borderVisible: false, timeVisible: true, secondsVisible: false, rightOffset: 4 },
      localization: { priceFormatter: (price: number) => formatNumber(price, 2) },
    });
    this.candleSeries = chart.addSeries(CandlestickSeries, { borderVisible: false, priceLineVisible: false });
    this.volumeSeries = chart.addSeries(HistogramSeries, {
      priceScaleId: 'volume',
      priceFormat: { type: 'volume' },
      lastValueVisible: false,
      priceLineVisible: false,
    });
    chart.priceScale('volume').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } });
    this.smaSeries = chart.addSeries(LineSeries, {
      lineWidth: 1,
      priceLineVisible: false,
      lastValueVisible: false,
      crosshairMarkerVisible: false,
    });
    chart.subscribeCrosshairMove((event) => this.onCrosshair(event));
    this.chart = chart;
    this.applyTheme();
    this.drawCandles();
    this.drawLivePrice();
  }

  private applyTheme(): void {
    const up = cssVar('--positive');
    const down = cssVar('--negative');
    const grid = cssVar('--chart-grid');
    this.chart?.applyOptions({
      layout: { textColor: cssVar('--chart-text'), fontFamily: cssVar('--font-mono'), fontSize: 11 },
      grid: { vertLines: { color: grid }, horzLines: { color: grid } },
      crosshair: {
        vertLine: { color: cssVar('--border-strong'), labelBackgroundColor: cssVar('--surface-active') },
        horzLine: { color: cssVar('--border-strong'), labelBackgroundColor: cssVar('--surface-active') },
      },
    });
    this.candleSeries?.applyOptions({ upColor: up, downColor: down, wickUpColor: up, wickDownColor: down });
    this.smaSeries?.applyOptions({ color: cssVar('--info') });
    this.livePrice?.applyOptions({ color: cssVar('--accent') });
    // Volume colors are per bar: redraw them all
    this.drawn = [];
    this.drawCandles();
  }

  private drawCandles(): void {
    const series = this.candleSeries;
    if (!series || !this.volumeSeries || !this.smaSeries) {
      return;
    }
    const candles = this.store.candles();
    const appendedOne =
      candles.length > 0 &&
      this.drawn.length > 0 &&
      candles.length >= this.drawn.length &&
      candles.at(-2)?.openTime === this.drawn.at(-1)?.openTime;
    const up = withAlpha(cssVar('--positive'), 0.35);
    const down = withAlpha(cssVar('--negative'), 0.35);
    const bar = (candle: Candle): CandlestickData<Time> => ({
      time: toTime(candle.openTime),
      open: candle.open,
      high: candle.high,
      low: candle.low,
      close: candle.close,
    });
    const volume = (candle: Candle) => ({
      time: toTime(candle.openTime),
      value: candle.volume,
      color: candle.close >= candle.open ? up : down,
    });
    const averages = movingAverage(candles.map((candle) => candle.close), SMA_PERIOD);

    if (appendedOne) {
      const last = candles[candles.length - 1];
      series.update(bar(last));
      this.volumeSeries.update(volume(last));
      const average = averages.at(-1);
      if (average !== undefined) {
        this.smaSeries.update({ time: toTime(last.openTime), value: average });
      }
    } else {
      series.setData(candles.map(bar));
      this.volumeSeries.setData(candles.map(volume));
      this.smaSeries.setData(
        candles.flatMap((candle, index) => {
          const value = averages[index];
          return value === undefined ? [] : [{ time: toTime(candle.openTime), value }];
        }),
      );
      if (this.drawn.length === 0 && candles.length) {
        this.chart?.timeScale().fitContent();
      }
    }
    this.drawn = candles;
  }

  private drawLivePrice(): void {
    const price = this.store.price();
    if (!this.candleSeries || price === null) {
      return;
    }
    if (this.livePrice) {
      this.livePrice.applyOptions({ price });
    } else {
      this.livePrice = this.candleSeries.createPriceLine({
        price,
        color: cssVar('--accent'),
        lineWidth: 1,
        lineStyle: LineStyle.Dashed,
        axisLabelVisible: true,
        title: '',
      });
    }
  }

  private onCrosshair(event: MouseEventParams<Time>): void {
    const data = (this.candleSeries ? event.seriesData.get(this.candleSeries) : undefined) as
      | CandlestickData<Time>
      | undefined;
    if (!event.time || !data || !('open' in data)) {
      this.legend.set(null);
      return;
    }
    const volume = (this.volumeSeries ? event.seriesData.get(this.volumeSeries) : undefined) as
      | HistogramData<Time>
      | undefined;
    this.legend.set({
      // The axis is in UTC; so is the legend
      time: `${new Date(Number(event.time) * 1000).toISOString().slice(11, 16)} UTC`,
      open: formatNumber(data.open),
      high: formatNumber(data.high),
      low: formatNumber(data.low),
      close: formatNumber(data.close),
      volume: volume && 'value' in volume ? formatCompact(volume.value, 2) : '—',
      rising: data.close >= data.open,
    });
  }
}
