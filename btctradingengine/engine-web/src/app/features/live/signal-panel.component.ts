import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { formatPercent } from '../../core/format/format';
import { ClockPipe, NumPipe, PctPipe } from '../../shared/pipes/format.pipes';
import { BadgeComponent } from '../../shared/ui/badge.component';
import { CardComponent } from '../../shared/ui/card.component';
import { EmptyStateComponent, SkeletonComponent } from '../../shared/ui/feedback.components';
import { IconComponent } from '../../shared/ui/icon.component';
import { TooltipDirective } from '../../shared/ui/tooltip.directive';
import { REGIME_TIP, REGIMES, SIGNALS } from './labels';
import { LiveStore } from './live.store';

/**
 * What the model decided and why: the signal, its confidence, the up/down probabilities, the regime and the
 * rationale. One place for what the old dashboard split between "Model output" and "Decision".
 */
@Component({
  selector: 'bte-signal-panel',
  imports: [CardComponent, BadgeComponent, IconComponent, SkeletonComponent, EmptyStateComponent, TooltipDirective, NumPipe, PctPipe, ClockPipe],
  template: `
    <bte-card heading="Sinal do modelo" [subtitle]="subtitle()" icon="target">
      @if (store.prediction(); as prediction) {
        @if (!prediction.entryAllowed) {
          <bte-badge
            card-actions
            tone="warning"
            bteTooltip="Regras de filtro ou uma trava de entrada (VPIN, order book) estão bloqueando novas entradas. Saídas continuam valendo."
            tabindex="0"
          >
            <bte-icon name="ban" [size]="12" /> Entradas bloqueadas
          </bte-badge>
        }

        <div class="verdict" [class]="'verdict tone-' + view().tone" aria-live="polite">
          <span class="verdict-icon"><bte-icon [name]="view().icon" [size]="22" /></span>
          <div class="verdict-text">
            <p class="verdict-word">{{ view().label }}</p>
            <p class="verdict-caption">{{ view().caption }} · {{ prediction.price | num: 2 }} USDT</p>
          </div>
          <div class="confidence">
            <span class="confidence-value num">{{ confidence() }}</span>
            <span
              class="confidence-label has-tip"
              tabindex="0"
              bteTooltip="Magnitude do score do modelo: quanto maior, mais forte o sinal."
            >confiança</span>
          </div>
        </div>

        <div class="probabilities">
          <div class="probability">
            <div class="probability-head">
              <span class="has-tip" tabindex="0" bteTooltip="Probabilidade estimada de a próxima vela fechar em alta.">Alta</span>
              <span class="num">{{ prediction.probabilityUp | pct: 1 : true }}</span>
            </div>
            <div
              class="bar"
              role="meter"
              aria-label="Probabilidade de alta"
              aria-valuemin="0"
              aria-valuemax="100"
              [attr.aria-valuenow]="up()"
            >
              <span class="fill up" [style.width.%]="up()"></span>
            </div>
          </div>
          <div class="probability">
            <div class="probability-head">
              <span class="has-tip" tabindex="0" bteTooltip="Probabilidade estimada de a próxima vela fechar em baixa.">Baixa</span>
              <span class="num">{{ prediction.probabilityDown | pct: 1 : true }}</span>
            </div>
            <div
              class="bar"
              role="meter"
              aria-label="Probabilidade de baixa"
              aria-valuemin="0"
              aria-valuemax="100"
              [attr.aria-valuenow]="down()"
            >
              <span class="fill down" [style.width.%]="down()"></span>
            </div>
          </div>
        </div>

        <dl class="details">
          <div>
            <dt class="has-tip" tabindex="0" [bteTooltip]="regimeTip">Regime</dt>
            <dd><bte-badge [tone]="regime().tone">{{ regime().label }}</bte-badge></dd>
          </div>
          <div>
            <dt>Gerado às</dt>
            <dd class="num">{{ prediction.timestamp | clock }}</dd>
          </div>
          <div>
            <dt>Modelo</dt>
            <dd class="num">{{ prediction.modelVersion || '—' }}</dd>
          </div>
        </dl>

        <div class="rationale">
          <p class="eyebrow">Justificativa</p>
          <p class="rationale-text">{{ prediction.reason || 'O modelo não informou uma justificativa.' }}</p>
        </div>
      } @else if (store.loading()) {
        <div class="loading" aria-busy="true" aria-label="Carregando sinal">
          <bte-skeleton height="56px" radius="var(--radius-md)" />
          <bte-skeleton height="10px" />
          <bte-skeleton height="10px" width="80%" />
          <bte-skeleton height="48px" />
        </div>
      } @else {
        <bte-empty-state
          icon="target"
          title="Nenhum sinal ainda"
          description="O modelo emite o primeiro sinal quando houver velas suficientes para os indicadores."
        />
      }
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

    .verdict {
      --verdict: var(--warning);
      --verdict-soft: var(--warning-soft);

      display: flex;
      align-items: center;
      gap: var(--space-4);
      padding: var(--space-4);
      border-radius: var(--radius-md);
      background: var(--verdict-soft);
      transition: background-color var(--dur-slow) var(--ease-out);
    }

    .verdict.tone-positive {
      --verdict: var(--positive);
      --verdict-soft: var(--positive-soft);
    }

    .verdict.tone-negative {
      --verdict: var(--negative);
      --verdict-soft: var(--negative-soft);
    }

    .verdict-icon {
      display: grid;
      place-items: center;
      flex-shrink: 0;
      width: 44px;
      height: 44px;
      border-radius: var(--radius-md);
      background: var(--surface);
      color: var(--verdict);
      transition: color var(--dur-slow) var(--ease-out);
    }

    .verdict-text {
      flex: 1;
      min-width: 0;
    }

    .verdict-word {
      font-size: var(--text-xl);
      font-weight: var(--weight-semibold);
      letter-spacing: var(--tracking-tight);
      color: var(--verdict);
      transition: color var(--dur-slow) var(--ease-out);
    }

    .verdict-caption {
      margin-top: 2px;
      font-size: var(--text-xs);
      color: var(--text-muted);
    }

    .confidence {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: 2px;
    }

    .confidence-value {
      font-size: var(--text-xl);
      font-weight: var(--weight-semibold);
    }

    .confidence-label {
      font-size: var(--text-2xs);
      color: var(--text-subtle);
    }

    .probabilities {
      display: grid;
      gap: var(--space-3);
      margin-top: var(--space-5);
    }

    .probability-head {
      display: flex;
      justify-content: space-between;
      margin-bottom: 6px;
      font-size: var(--text-xs);
      color: var(--text-muted);
    }

    .probability-head .num {
      color: var(--text);
    }

    .bar {
      height: 6px;
      border-radius: var(--radius-full);
      background: var(--surface-2);
      overflow: hidden;
    }

    .fill {
      display: block;
      height: 100%;
      border-radius: inherit;
      transition: width var(--dur-slow) var(--ease-out);
    }

    .fill.up {
      background: var(--positive);
    }

    .fill.down {
      background: var(--negative);
    }

    .details {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-3) var(--space-6);
      margin-top: var(--space-5);
      padding-top: var(--space-4);
      border-top: 1px solid var(--border);
    }

    .details div {
      display: flex;
      flex-direction: column;
      gap: 6px;
      min-width: 0;
    }

    dt {
      width: fit-content;
      font-size: var(--text-xs);
      color: var(--text-subtle);
    }

    dd {
      margin: 0;
      font-size: var(--text-sm);
    }

    .rationale {
      margin-top: var(--space-4);
      padding: var(--space-3) var(--space-4);
      border-radius: var(--radius-md);
      background: var(--surface-2);
    }

    .rationale-text {
      margin-top: var(--space-1);
      font-size: var(--text-sm);
      line-height: 1.6;
      color: var(--text-muted);
      overflow-wrap: anywhere;
    }

    .loading {
      display: grid;
      gap: var(--space-3);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SignalPanelComponent {
  protected readonly store = inject(LiveStore);
  protected readonly regimeTip = REGIME_TIP;

  protected readonly view = computed(() => SIGNALS[this.store.prediction()?.signal ?? 'HOLD'] ?? SIGNALS.HOLD);
  protected readonly regime = computed(
    () => REGIMES[this.store.prediction()?.marketRegime ?? 'UNKNOWN'] ?? REGIMES.UNKNOWN,
  );
  protected readonly confidence = computed(() =>
    formatPercent(this.store.prediction()?.confidence, 0, { fraction: true }),
  );
  protected readonly up = computed(() => clampPercent(this.store.prediction()?.probabilityUp));
  protected readonly down = computed(() => clampPercent(this.store.prediction()?.probabilityDown));
  protected readonly subtitle = computed(() => {
    const prediction = this.store.prediction();
    return prediction ? `Decisão do bot na última vela · ${prediction.instrument}` : 'Decisão do bot';
  });
}

function clampPercent(value: number | null | undefined): number {
  return Math.round(Math.min(Math.max((value ?? 0) * 100, 0), 100));
}
