import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { isNumber, toneOf } from '../../core/format/format';
import { AuthService } from '../../core/auth/auth.service';
import { DayPipe, NumPipe, PctPipe } from '../../shared/pipes/format.pipes';
import { BadgeComponent } from '../../shared/ui/badge.component';
import { CardComponent } from '../../shared/ui/card.component';
import { ConfirmService } from '../../shared/ui/confirm';
import { EmptyStateComponent, SkeletonComponent, SpinnerComponent } from '../../shared/ui/feedback.components';
import { IconComponent } from '../../shared/ui/icon.component';
import { StatComponent } from '../../shared/ui/stat.component';
import { ToastService } from '../../shared/ui/toast';
import { TooltipDirective } from '../../shared/ui/tooltip.directive';
import { POSITION_STATES, SIDES } from './labels';
import { LiveStore, ManualAction } from './live.store';

/**
 * The open position and the manual orders. Both orders ask for confirmation, report the result as a toast,
 * and are disabled with an explanation for a login without the TRADER role.
 */
@Component({
  selector: 'bte-position-panel',
  imports: [
    CardComponent,
    BadgeComponent,
    StatComponent,
    IconComponent,
    SpinnerComponent,
    SkeletonComponent,
    EmptyStateComponent,
    TooltipDirective,
    NumPipe,
    PctPipe,
    DayPipe,
  ],
  template: `
    <bte-card heading="Posição" [subtitle]="subtitle()" icon="wallet">
      <div card-actions class="actions" [bteTooltip]="lockedReason()">
        <button
          type="button"
          class="btn btn-positive btn-sm"
          [disabled]="!canBuy()"
          [attr.aria-busy]="store.pendingAction() === 'buy'"
          (click)="run('buy')"
        >
          @if (store.pendingAction() === 'buy') {
            <bte-spinner [size]="12" />
          } @else {
            <bte-icon name="trending-up" [size]="14" />
          }
          Comprar
        </button>
        <button
          type="button"
          class="btn btn-negative btn-sm"
          [disabled]="!canClose()"
          [attr.aria-busy]="store.pendingAction() === 'close'"
          (click)="run('close')"
        >
          @if (store.pendingAction() === 'close') {
            <bte-spinner [size]="12" />
          } @else {
            <bte-icon name="x" [size]="14" />
          }
          Encerrar
        </button>
      </div>

      @if (store.position(); as position) {
        <div class="position" animate.enter="enter-fade-up">
          <div class="headline">
            <div class="badges">
              <bte-badge [tone]="position.signal === 'SELL' ? 'negative' : 'positive'" size="md">
                {{ sides[position.signal] }}
              </bte-badge>
              <bte-badge [tone]="state().tone" [dot]="true" [pulse]="position.state !== 'OPEN'" size="md">
                {{ state().label }}
              </bte-badge>
            </div>
            <div class="pnl" [class]="'pnl tone-' + pnlTone()">
              <span class="pnl-value num">{{ position.pnL | num: 2 : true }}</span>
              <span class="pnl-meta num">pts · {{ position.pnLPercent | pct: 2 : false : true }}</span>
            </div>
          </div>

          <div class="range" role="img" [attr.aria-label]="rangeLabel()">
            <div class="range-track">
              <span class="range-marker entry" [style.left.%]="range().entry" aria-hidden="true"></span>
              <span class="range-marker current" [style.left.%]="range().current" aria-hidden="true"></span>
            </div>
            <div class="range-labels num" aria-hidden="true">
              <span class="tone-negative">Stop {{ position.stopLossPrice | num: 2 }}</span>
              <span class="tone-positive">Alvo {{ position.targetPrice | num: 2 }}</span>
            </div>
          </div>

          <div class="grid">
            <bte-stat label="Entrada" [value]="position.entryPrice | num: 2" [hint]="position.entryTime | day: 'time'" size="sm"
              tip="Preço e horário em que a posição foi aberta." />
            <bte-stat label="Preço atual" [value]="position.currentPrice | num: 2" hint="usado no P&L" size="sm"
              tip="Último preço de mercado aplicado à posição." />
            <bte-stat label="Alvo" [value]="position.targetPrice | num: 2" [hint]="'+' + (position.targetPercent | num: 2) + '%'" size="sm"
              tip="Take-profit: a posição é encerrada automaticamente neste preço." />
            <bte-stat label="Stop" [value]="position.stopLossPrice | num: 2" [hint]="'−' + (position.stopLossPercent | num: 2) + '%'" size="sm"
              tip="Stop loss: a posição é encerrada automaticamente neste preço." />
          </div>
          <p class="id num">ID {{ position.positionId }}</p>
        </div>
      } @else if (store.loading()) {
        <div class="loading" aria-busy="true" aria-label="Carregando posição">
          <bte-skeleton height="28px" width="60%" />
          <bte-skeleton height="8px" />
          <bte-skeleton height="44px" />
        </div>
      } @else {
        <bte-empty-state
          icon="wallet"
          title="Nenhuma posição aberta"
          description="O bot abre uma posição quando o sinal se confirma. Você também pode comprar manualmente a mercado."
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

    .actions {
      display: flex;
      gap: var(--space-2);
    }

    .headline {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: var(--space-3);
    }

    .badges {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
    }

    .pnl {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
    }

    .pnl-value {
      font-size: var(--text-2xl);
      font-weight: var(--weight-semibold);
      letter-spacing: var(--tracking-tight);
      line-height: 1.1;
    }

    .pnl-meta {
      font-size: var(--text-xs);
      color: var(--text-muted);
    }

    .pnl.tone-positive .pnl-value {
      color: var(--positive);
    }

    .pnl.tone-negative .pnl-value {
      color: var(--negative);
    }

    .range {
      margin-top: var(--space-5);
    }

    .range-track {
      position: relative;
      height: 6px;
      border-radius: var(--radius-full);
      background: linear-gradient(90deg, var(--negative-soft), var(--surface-2) 50%, var(--positive-soft));
    }

    .range-marker {
      position: absolute;
      top: 50%;
      width: 12px;
      height: 12px;
      border-radius: 50%;
      transform: translate(-50%, -50%);
      transition: left var(--dur-slow) var(--ease-out);
    }

    .range-marker.entry {
      width: 2px;
      height: 14px;
      border-radius: 1px;
      background: var(--text-subtle);
    }

    .range-marker.current {
      background: var(--accent);
      box-shadow: 0 0 0 3px var(--surface);
    }

    .range-labels {
      display: flex;
      justify-content: space-between;
      margin-top: var(--space-2);
      font-size: var(--text-2xs);
    }

    .grid {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: var(--space-4);
      margin-top: var(--space-5);
    }

    .id {
      margin-top: var(--space-4);
      font-size: var(--text-2xs);
      color: var(--text-subtle);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .loading {
      display: grid;
      gap: var(--space-4);
    }

    @media (max-width: 560px) {
      .grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PositionPanelComponent {
  protected readonly store = inject(LiveStore);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  protected readonly sides = SIDES;

  protected readonly state = computed(() => POSITION_STATES[this.store.position()?.state ?? 'OPEN']);
  protected readonly pnlTone = computed(() => toneOf(this.store.position()?.pnL));
  protected readonly subtitle = computed(() =>
    this.store.position() ? 'Exposição atual' : 'Sem exposição',
  );

  protected readonly lockedReason = computed(() =>
    this.auth.session() && !this.auth.isTrader() ? 'Enviar ordens requer o perfil TRADER' : '',
  );
  protected readonly canBuy = computed(
    () => this.auth.isTrader() && !this.store.position() && !this.store.pendingAction() && this.store.price() !== null,
  );
  protected readonly canClose = computed(
    () => this.auth.isTrader() && !!this.store.position() && !this.store.pendingAction(),
  );

  /** Where entry and current price sit between stop (0 %) and target (100 %). */
  protected readonly range = computed(() => {
    const position = this.store.position();
    const stop = position?.stopLossPrice;
    const target = position?.targetPrice;
    if (!position || !isNumber(stop) || !isNumber(target) || target === stop) {
      return { entry: 50, current: 50 };
    }
    const place = (price: number | null | undefined) =>
      isNumber(price) ? Math.min(100, Math.max(0, ((price - stop) / (target - stop)) * 100)) : 50;
    return { entry: place(position.entryPrice), current: place(position.currentPrice ?? position.entryPrice) };
  });

  protected readonly rangeLabel = computed(
    () => `Preço atual a ${Math.round(this.range().current)}% do caminho entre o stop e o alvo`,
  );

  protected async run(action: ManualAction): Promise<void> {
    const confirmed = await this.confirm.ask(
      action === 'buy'
        ? {
            title: 'Comprar a mercado?',
            message: 'Abre uma posição de compra agora, ao preço de mercado, com o alvo e o stop configurados.',
            confirmLabel: 'Comprar',
            tone: 'positive',
            icon: 'trending-up',
          }
        : {
            title: 'Encerrar a posição?',
            message: 'Envia uma ordem a mercado para fechar a posição aberta agora.',
            confirmLabel: 'Encerrar posição',
            tone: 'danger',
            icon: 'alert-triangle',
          },
    );
    if (!confirmed) {
      return;
    }
    const outcome = await this.store.manual(action);
    if (outcome.ok) {
      this.toasts.success(action === 'buy' ? 'Ordem de compra enviada' : 'Encerramento enviado', outcome.message);
    } else if (outcome.forbidden) {
      this.toasts.warning('Sem permissão', outcome.message);
    } else {
      this.toasts.error(action === 'buy' ? 'A compra não foi feita' : 'A posição não foi encerrada', outcome.message);
    }
  }
}
