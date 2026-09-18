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
import { ThemeService } from '../../core/theme/theme.service';
import { Chart, cssVar, frameScheduler, withAlpha } from './chart-support';

/** A tiny line of the last prices, without axes: shape over precision. */
@Component({
  selector: 'bte-sparkline',
  template: `<canvas #canvas role="img" [attr.aria-label]="label()"></canvas>`,
  styles: `
    :host {
      display: block;
      position: relative;
      height: 100%;
      min-height: 32px;
    }

    canvas {
      position: absolute;
      inset: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SparklineComponent {
  readonly values = input.required<number[]>();
  readonly label = input('Variação recente do preço');

  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private readonly theme = inject(ThemeService);
  private chart: Chart<'line'> | null = null;
  private readonly redraw = frameScheduler(() => this.draw());

  constructor() {
    afterNextRender(() => this.create());
    effect(() => {
      this.values();
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

  private create(): void {
    this.chart = new Chart(this.canvas().nativeElement, {
      type: 'line',
      data: { labels: [], datasets: [{ data: [], borderWidth: 1.5, pointRadius: 0, tension: 0.35, fill: true }] },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        events: [],
        plugins: { legend: { display: false }, tooltip: { enabled: false } },
        scales: { x: { display: false }, y: { display: false, grace: '12%' } },
      },
    });
    this.draw();
  }

  private draw(): void {
    const chart = this.chart;
    if (!chart) {
      return;
    }
    const values = this.values();
    const rising = values.length < 2 || values[values.length - 1] >= values[0];
    const color = cssVar(rising ? '--positive' : '--negative');
    const dataset = chart.data.datasets[0];
    chart.data.labels = values.map((_, index) => index);
    dataset.data = values;
    dataset.borderColor = color;
    dataset.backgroundColor = withAlpha(color, 0.1);
    chart.update('none');
  }
}
