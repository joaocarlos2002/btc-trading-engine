import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toneOf } from '../../core/format/format';
import { Position } from '../../core/models/live.models';
import { DayPipe, NumPipe, PctPipe } from '../../shared/pipes/format.pipes';
import { BadgeComponent } from '../../shared/ui/badge.component';
import { CardComponent } from '../../shared/ui/card.component';
import { EmptyStateComponent, SkeletonComponent } from '../../shared/ui/feedback.components';
import { EXIT_REASONS, SIDES } from './labels';
import { LiveStore } from './live.store';

const COLLAPSED_ROWS = 10;

/** Closed trades, newest first: a table on wide screens, a card list on phones. */
@Component({
  selector: 'bte-trades-table',
  imports: [CardComponent, BadgeComponent, EmptyStateComponent, SkeletonComponent, NumPipe, PctPipe, DayPipe],
  template: `
    <bte-card heading="Trades fechados" [subtitle]="subtitle()" icon="history" [flush]="true">
      @if (total() > collapsedRows) {
        <button card-actions type="button" class="btn btn-ghost btn-sm" [attr.aria-expanded]="expanded()" (click)="expanded.set(!expanded())">
          {{ expanded() ? 'Mostrar menos' : 'Mostrar todos (' + total() + ')' }}
        </button>
      }

      @if (rows().length) {
        <div class="table-wrap desktop">
          <table class="table">
            <caption class="sr-only">Trades fechados, do mais recente para o mais antigo</caption>
            <thead>
              <tr>
                <th scope="col">Fechado</th>
                <th scope="col">Lado</th>
                <th scope="col" class="align-end">Entrada</th>
                <th scope="col" class="align-end">Saída</th>
                <th scope="col" class="align-end">P&amp;L (pts)</th>
                <th scope="col">Motivo</th>
                <th scope="col">ID</th>
              </tr>
            </thead>
            <tbody>
              @for (trade of rows(); track trade.positionId) {
                <tr animate.enter="enter-fade">
                  <td class="num muted">{{ trade.exitTime | day: 'time' }}</td>
                  <td>
                    <span class="side" [class.sell]="trade.signal === 'SELL'">{{ sides[trade.signal] }}</span>
                  </td>
                  <td class="num align-end">{{ trade.entryPrice | num: 2 }}</td>
                  <td class="num align-end">{{ trade.exitPrice | num: 2 }}</td>
                  <td class="num align-end" [class]="'num align-end tone-' + tone(trade)">
                    {{ trade.pnL | num: 2 : true }}
                    <span class="muted">{{ trade.pnLPercent | pct: 2 : false : true }}</span>
                  </td>
                  <td>
                    @if (trade.exitReason; as reason) {
                      <bte-badge [tone]="reasons[reason].tone">{{ reasons[reason].label }}</bte-badge>
                    } @else {
                      —
                    }
                  </td>
                  <td class="num muted id">{{ trade.positionId }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        <ul class="cards mobile">
          @for (trade of rows(); track trade.positionId) {
            <li class="trade">
              <div class="trade-top">
                <span class="side" [class.sell]="trade.signal === 'SELL'">{{ sides[trade.signal] }}</span>
                <span class="num" [class]="'num pnl tone-' + tone(trade)">
                  {{ trade.pnL | num: 2 : true }} pts
                </span>
              </div>
              <div class="trade-mid num">
                {{ trade.entryPrice | num: 2 }} → {{ trade.exitPrice | num: 2 }}
                <span class="muted">({{ trade.pnLPercent | pct: 2 : false : true }})</span>
              </div>
              <div class="trade-bottom">
                @if (trade.exitReason; as reason) {
                  <bte-badge [tone]="reasons[reason].tone">{{ reasons[reason].label }}</bte-badge>
                }
                <span class="num muted">{{ trade.exitTime | day: 'time' }}</span>
              </div>
            </li>
          }
        </ul>
      } @else if (store.loading()) {
        <div class="loading" aria-busy="true" aria-label="Carregando trades">
          @for (row of [1, 2, 3]; track row) {
            <bte-skeleton height="18px" />
          }
        </div>
      } @else {
        <bte-empty-state
          icon="history"
          title="Nenhum trade fechado ainda"
          description="Cada posição encerrada, por alvo, stop, sinal contrário ou manualmente, aparece aqui."
        />
      }
    </bte-card>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }

    .muted {
      color: var(--text-subtle);
    }

    .id {
      max-width: 180px;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    td.tone-positive {
      color: var(--positive);
    }

    td.tone-negative {
      color: var(--negative);
    }

    td .muted {
      margin-left: var(--space-1);
      font-size: var(--text-xs);
    }

    .side {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-weight: var(--weight-medium);
    }

    .side::before {
      content: '';
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--positive);
    }

    .side.sell::before {
      background: var(--negative);
    }

    .cards {
      display: none;
      list-style: none;
      padding: 0 var(--space-4) var(--space-4);
      margin: 0;
    }

    .trade {
      display: grid;
      gap: var(--space-2);
      padding: var(--space-3) 0;
      border-bottom: 1px solid var(--border);
    }

    .trade:last-child {
      border-bottom: 0;
    }

    .trade-top,
    .trade-bottom {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
    }

    .trade-mid {
      font-size: var(--text-sm);
      color: var(--text-muted);
    }

    .pnl {
      font-weight: var(--weight-semibold);
    }

    .pnl.tone-positive {
      color: var(--positive);
    }

    .pnl.tone-negative {
      color: var(--negative);
    }

    .trade-bottom .muted {
      font-size: var(--text-xs);
    }

    .loading {
      display: grid;
      gap: var(--space-3);
      padding: var(--space-4) var(--space-5) var(--space-5);
    }

    @media (max-width: 640px) {
      .desktop {
        display: none;
      }

      .cards {
        display: block;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TradesTableComponent {
  protected readonly store = inject(LiveStore);
  protected readonly sides = SIDES;
  protected readonly reasons = EXIT_REASONS;
  protected readonly collapsedRows = COLLAPSED_ROWS;
  protected readonly expanded = signal(false);

  private readonly newestFirst = computed(() => [...this.store.closedTrades()].reverse());
  protected readonly total = computed(() => this.newestFirst().length);
  protected readonly rows = computed(() =>
    this.expanded() ? this.newestFirst() : this.newestFirst().slice(0, COLLAPSED_ROWS),
  );
  protected readonly subtitle = computed(() =>
    this.total() ? `${this.total()} mais recentes · P&L por unidade, em pontos de preço` : 'Histórico da sessão',
  );

  protected tone(trade: Position): string {
    return toneOf(trade.pnL);
  }
}
