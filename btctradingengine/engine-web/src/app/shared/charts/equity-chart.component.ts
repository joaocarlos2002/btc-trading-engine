import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  viewChild,
} from '@angular/core';
import { formatNumber } from '../../core/format/format';
import { ThemeService } from '../../core/theme/theme.service';
import { Chart, cssVar, frameScheduler, prefersReducedMotion, withAlpha } from './chart-support';

/** Cumulative P&L after each closed trade; green above zero, red below. */
@Component({
  selector: 'bte-equity-chart',
  template: `<canvas #canvas role="img" [attr.aria-label]="summary()"></canvas>`,
  styles: `
    :host {
      display: block;
      position: relative;
      height: 100%;
      min-height: 160px;
    }

    canvas {
      position: absolute;
      inset: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EquityChartComponent {
  /** Cumulative P&L, one point per trade in order */
  readonly points = input.required<number[]>();
  readonly unit = input('pts');

  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private readonly theme = inject(ThemeService);
  private chart: Chart<'line'> | null = null;
  private readonly redraw = frameScheduler(() => this.draw());

  constructor() {
    afterNextRender(() => this.create());
    effect(() => {
      this.points();
      this.redraw.schedule();
    });
    effect(() => {
      this.theme.theme();
      this.redraw.schedule();
    });
    inject(DestroyRef).onDestroy(() => {
      this.redraw.cancel();
      this.chart?.destroy();
    });
  }

  protected summary(): string {
    const points = this.points();
    const last = points.at(-1);
    return last === undefined
      ? 'Curva de P&L sem trades'
      : `Curva de P&L de ${points.length} trades, acumulado ${formatNumber(last)} ${this.unit()}`;
  }

  private create(): void {
    this.chart = new Chart(this.canvas().nativeElement, {
      type: 'line',
      data: { labels: [], datasets: [{ data: [], borderWidth: 2, pointRadius: 0, pointHoverRadius: 4, tension: 0.25, fill: 'origin' }] },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: prefersReducedMotion() ? false : { duration: 300 },
        interaction: { mode: 'index', intersect: false },
        layout: { padding: { top: 8, right: 4 } },
        plugins: {
          legend: { display: false },
          tooltip: {
            displayColors: false,
            padding: 10,
            cornerRadius: 8,
            callbacks: {
              title: (items) => `Trade #${items[0]?.label ?? ''}`,
              label: (item) => `Acumulado ${formatNumber(item.parsed.y, 2, true)} ${this.unit()}`,
            },
          },
        },
        scales: {
          x: { grid: { display: false }, border: { display: false }, ticks: { maxTicksLimit: 8 } },
          y: {
            position: 'right',
            border: { display: false },
            ticks: { maxTicksLimit: 5, callback: (value) => formatNumber(Number(value), 0) },
          },
        },
      },
    });
    this.draw();
  }

  private draw(): void {
    const chart = this.chart;
    if (!chart) {
      return;
    }
    const points = this.points();
    const color = cssVar((points.at(-1) ?? 0) >= 0 ? '--positive' : '--negative');
    const text = cssVar('--chart-text');
    const grid = cssVar('--chart-grid');
    const dataset = chart.data.datasets[0];
    chart.data.labels = points.map((_, index) => index + 1);
    dataset.data = points;
    dataset.borderColor = color;
    dataset.backgroundColor = withAlpha(color, 0.08);
    dataset.pointHoverBackgroundColor = color;
    const scales = chart.options.scales ?? {};
    for (const axis of [scales['x'], scales['y']]) {
      if (axis?.ticks) {
        axis.ticks.color = text;
        axis.ticks.font = { family: cssVar('--font-mono'), size: 11 };
      }
    }
    if (scales['y']?.grid) {
      scales['y'].grid.color = grid;
    }
    const tooltip = chart.options.plugins?.tooltip;
    if (tooltip) {
      tooltip.backgroundColor = cssVar('--surface-2');
      tooltip.borderColor = cssVar('--border-strong');
      tooltip.borderWidth = 1;
      tooltip.titleColor = cssVar('--text-muted');
      tooltip.bodyColor = cssVar('--text');
    }
    chart.update();
  }
}
