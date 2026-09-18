import { BadgeTone } from '../../shared/ui/badge.component';
import { IconName } from '../../shared/ui/icon.component';
import { ExitReason, MarketRegime, PositionState, TradeSignal } from '../../core/models/live.models';

export interface SignalView {
  label: string;
  caption: string;
  icon: IconName;
  tone: BadgeTone;
}

export const SIGNALS: Record<TradeSignal, SignalView> = {
  BUY: { label: 'Comprar', caption: 'Setup de compra', icon: 'trending-up', tone: 'positive' },
  SELL: { label: 'Vender', caption: 'Setup de venda', icon: 'trending-down', tone: 'negative' },
  HOLD: { label: 'Aguardar', caption: 'Sem setup no momento', icon: 'minus', tone: 'warning' },
};

export const REGIMES: Record<MarketRegime, { label: string; tone: BadgeTone }> = {
  TREND_UP: { label: 'Tendência de alta', tone: 'positive' },
  TREND_DOWN: { label: 'Tendência de baixa', tone: 'negative' },
  RANGE: { label: 'Lateral', tone: 'neutral' },
  SQUEEZE: { label: 'Compressão', tone: 'info' },
  TRANSITION: { label: 'Transição', tone: 'warning' },
  UNKNOWN: { label: 'Aquecendo', tone: 'neutral' },
};

export const REGIME_TIP =
  'Regime em que a decisão foi tomada: tendência (ADX forte com +DI/−DI e inclinação da EMA concordando), ' +
  'lateral (ADX baixo), compressão (bandas estreitas), transição ou aquecendo. Com prediction.regime.gating.enabled, ' +
  'tendência desliga RSI/MFI/SMA e lateral desliga o MACD.';

export const POSITION_STATES: Record<PositionState, { label: string; tone: BadgeTone }> = {
  PENDING_ENTRY: { label: 'Entrada pendente', tone: 'warning' },
  OPEN: { label: 'Aberta', tone: 'accent' },
  EXIT_PENDING: { label: 'Saída pendente', tone: 'warning' },
  CLOSED: { label: 'Fechada', tone: 'neutral' },
  FAILED: { label: 'Falhou', tone: 'negative' },
};

export const EXIT_REASONS: Record<ExitReason, { label: string; tone: BadgeTone }> = {
  TARGET_HIT: { label: 'Alvo atingido', tone: 'positive' },
  STOP_LOSS: { label: 'Stop loss', tone: 'negative' },
  SIGNAL_REVERSAL: { label: 'Sinal contrário', tone: 'info' },
  MANUAL_CLOSE: { label: 'Manual', tone: 'neutral' },
  ORDER_FAILED: { label: 'Ordem falhou', tone: 'warning' },
  VALIDATION_FAILED: { label: 'Validação falhou', tone: 'warning' },
  ERROR: { label: 'Erro', tone: 'negative' },
};

export const SIDES: Record<TradeSignal, string> = { BUY: 'Compra', SELL: 'Venda', HOLD: '—' };
