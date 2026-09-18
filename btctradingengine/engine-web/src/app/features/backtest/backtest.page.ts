import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { formatDuration, formatPercent, toneOf } from '../../core/format/format';
import { DayPipe } from '../../shared/pipes/format.pipes';
import { CardComponent } from '../../shared/ui/card.component';
import { EmptyStateComponent, SpinnerComponent } from '../../shared/ui/feedback.components';
import { IconComponent } from '../../shared/ui/icon.component';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { BacktestFormComponent } from './backtest-form.component';
import { BacktestResultComponent } from './backtest-result.component';
import { BacktestStore, RunRecord } from './backtest.store';

/**
 * Backtest lab: configure on the left, follow the run and read the report on the right. Runs are jobs, so a
 * long range never times out, the page shows how long it has been running, and the last few results stay
 * one click away for comparison.
 */
@Component({
  selector: 'bte-backtest-page',
  imports: [
    PageHeaderComponent,
    CardComponent,
    IconComponent,
    SpinnerComponent,
    EmptyStateComponent,
    BacktestFormComponent,
    BacktestResultComponent,
    DayPipe,
  ],
  template: `
    <bte-page-header
      title="Backtest"
      description="Roda a estratégia configurada contra o histórico real da Binance, sem afetar o bot ao vivo."
    />

    <div class="layout">
      <bte-backtest-form class="form" [configParams]="configParams()" />

      <div #results class="results" aria-live="polite">
        @if (store.busy()) {
          <div class="status" role="status" animate.enter="enter-fade-up" animate.leave="leave-fade">
            <bte-spinner [size]="20" />
            <div class="status-text">
              <p class="status-title">
                {{ store.phase() === 'submitting' ? 'Enviando…' : store.job()?.status === 'PENDING' ? 'Na fila…' : 'Rodando o backtest…' }}
              </p>
              <p>
                Baixando {{ store.request()?.days }} dias de velas e simulando a estratégia.
                <span class="num">{{ elapsed() }}</span>
              </p>
            </div>
          </div>
        }

        @if (store.problem(); as problem) {
          <div class="problem" [class.busy]="problem.kind === 'busy'" role="alert" animate.enter="enter-fade-up" animate.leave="leave-fade">
            <bte-icon [name]="problem.kind === 'busy' ? 'clock' : 'alert-circle'" [size]="18" />
            <div class="problem-text">
              <p class="problem-title">{{ problem.message }}</p>
              @if (problem.errors.length) {
                <ul>
                  @for (error of problem.errors; track error) {
                    <li class="num">{{ error }}</li>
                  }
                </ul>
              }
            </div>
            <button type="button" class="btn btn-ghost btn-icon btn-sm" aria-label="Dispensar aviso" (click)="store.dismissProblem()">
              <bte-icon name="x" [size]="14" />
            </button>
          </div>
        }

        @if (store.selected(); as run) {
          <div [class.stale]="store.busy()">
            @if (store.busy()) {
              <p class="stale-note">Resultado anterior</p>
            }
            @for (current of [run]; track current.id) {
              <bte-backtest-result [run]="current" animate.enter="enter-fade-up" />
            }
          </div>
        } @else if (!store.busy()) {
          <bte-card>
            <bte-empty-state
              icon="flask"
              title="Nenhum backtest ainda"
              description="Escolha o período, ajuste os parâmetros se quiser e clique em Rodar backtest. Os resultados aparecem aqui."
            />
          </bte-card>
        }

        @if (store.history().length > 1) {
          <bte-card heading="Execuções recentes" subtitle="Clique para ver o resultado de novo" icon="history" [flush]="true">
            <ul class="history">
              @for (item of store.history(); track item.id) {
                <li>
                  <button
                    type="button"
                    class="history-item"
                    [class.active]="item.id === store.selected()?.id"
                    [attr.aria-current]="item.id === store.selected()?.id ? 'true' : null"
                    (click)="store.select(item.id)"
                  >
                    <span class="history-main">
                      <span class="history-title">{{ item.days }} dias · {{ describe(item) }}</span>
                      <span class="history-time num">{{ item.finishedAt | day: 'time' }}</span>
                    </span>
                    <span class="history-return num" [class]="'history-return num tone-' + tone(item)">{{ returnOf(item) }}</span>
                  </button>
                </li>
              }
            </ul>
          </bte-card>
        }
      </div>
    </div>
  `,
  styleUrl: './backtest.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BacktestPage {
  protected readonly store = inject(BacktestStore);
  private readonly now = signal(Date.now());
  private readonly results = viewChild.required<ElementRef<HTMLElement>>('results');

  /** The configured values: the last run's parameters minus what that run overrode */
  protected readonly configParams = computed(() => {
    const run = this.store.selected();
    if (!run) {
      return null;
    }
    const params = { ...run.result.params };
    for (const key of Object.keys(run.overrides)) {
      delete params[key];
    }
    return params;
  });

  protected readonly elapsed = computed(() => {
    const job = this.store.job();
    const start = job?.startedAt ?? job?.createdAt;
    return start ? `· ${formatDuration(this.now() - Date.parse(start))}` : '';
  });

  constructor() {
    // One column (tablet, phone): the run button is far below the results, so bring them into view
    effect(() => {
      if (this.store.phase() !== 'submitting') {
        return;
      }
      untracked(() => {
        const element = this.results().nativeElement;
        if (getComputedStyle(element).position !== 'sticky' && element.getBoundingClientRect().top < 0) {
          const smooth = !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
          element.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'start' });
        }
      });
    });
    const timer = setInterval(() => {
      if (this.store.busy()) {
        this.now.set(Date.now());
      }
    }, 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected describe(run: RunRecord): string {
    const count = Object.keys(run.overrides).length;
    return count ? `${count} ${count === 1 ? 'ajuste' : 'ajustes'}` : 'config padrão';
  }

  protected returnOf(run: RunRecord): string {
    return formatPercent(run.result.report.returnPercent, 2, { signed: true });
  }

  protected tone(run: RunRecord): string {
    return toneOf(run.result.report.returnPercent);
  }
}
