/**
 * The BacktestParams components the form can override (the same set the old backtest page had). Anything
 * left blank keeps the value of application.properties; nothing here changes the live bot.
 */
export interface ParamDef {
  key: string;
  label: string;
  step: number;
  min?: number;
  max?: number;
  integer?: boolean;
  hint?: string;
}

export interface ParamGroup {
  id: string;
  label: string;
  description: string;
  params: ParamDef[];
}

const period = (key: string, label: string): ParamDef => ({ key, label, step: 1, min: 1, integer: true });

export const PARAM_GROUPS: ParamGroup[] = [
  {
    id: 'entry',
    label: 'Entrada e confirmação',
    description: 'Quando o score vira sinal e quantas leituras confirmam',
    params: [
      { key: 'buyThreshold', label: 'Limiar de compra', step: 0.01, min: 0, max: 1, hint: '0 a 1' },
      { key: 'sellThreshold', label: 'Limiar de venda', step: 0.01, min: -1, max: 0, hint: '−1 a 0' },
      { key: 'confirmationSnapshots', label: 'Confirmações', step: 1, min: 1, integer: true, hint: 'leituras seguidas' },
    ],
  },
  {
    id: 'periods',
    label: 'Períodos dos indicadores',
    description: 'Tamanho das janelas, em velas',
    params: [
      period('smaPeriod', 'SMA'),
      period('emaPeriod', 'EMA'),
      period('rsiPeriod', 'RSI'),
      period('atrPeriod', 'ATR'),
      period('macdFastPeriod', 'MACD rápida'),
      period('macdSlowPeriod', 'MACD lenta'),
      period('macdSignalPeriod', 'MACD sinal'),
      period('volatilityShortPeriods', 'Volatilidade curta'),
      period('volatilityLongPeriods', 'Volatilidade longa'),
      period('volumeAveragePeriods', 'Média de volume'),
    ],
  },
  {
    id: 'rsi',
    label: 'Faixas do RSI',
    description: 'Onde o RSI passa a votar compra ou venda',
    params: [
      { key: 'rsiOversold', label: 'Sobrevendido', step: 0.1, min: 0, max: 100 },
      { key: 'rsiNeutralLow', label: 'Neutro baixo', step: 0.1, min: 0, max: 100 },
      { key: 'rsiNeutralHigh', label: 'Neutro alto', step: 0.1, min: 0, max: 100 },
      { key: 'rsiOverbought', label: 'Sobrecomprado', step: 0.1, min: 0, max: 100 },
    ],
  },
  {
    id: 'trend',
    label: 'SMA e MACD',
    description: 'Distâncias e força do histograma',
    params: [
      { key: 'smaDistanceExtreme', label: 'Distância extrema da SMA', step: 0.1, hint: '%' },
      { key: 'smaDistanceModerate', label: 'Distância moderada da SMA', step: 0.1, hint: '%' },
      { key: 'macdStrongHistogramAtrRatio', label: 'MACD forte', step: 0.01, hint: '× ATR' },
    ],
  },
  {
    id: 'volatility',
    label: 'Filtro de volatilidade',
    description: 'Faixas de ATR e razão de volatilidade que vetam entradas',
    params: [
      { key: 'atrVolatilityLow', label: 'ATR baixo', step: 0.001 },
      { key: 'atrVolatilityNormal', label: 'ATR normal', step: 0.001 },
      { key: 'atrVolatilityHigh', label: 'ATR alto', step: 0.001 },
      { key: 'volatilityRatioHigh', label: 'Razão de volatilidade alta', step: 0.1 },
    ],
  },
  {
    id: 'risk',
    label: 'Risco e custo',
    description: 'Saídas automáticas e comissão por lado',
    params: [
      { key: 'targetPercent', label: 'Alvo', step: 0.1, min: 0, hint: '%' },
      { key: 'stopLossPercent', label: 'Stop loss', step: 0.1, min: 0, hint: '%' },
      { key: 'commissionRate', label: 'Comissão', step: 0.0001, min: 0, hint: 'fração, ex. 0,001' },
    ],
  },
];

export const ALL_PARAMS: ParamDef[] = PARAM_GROUPS.flatMap((group) => group.params);

export const MIN_DAYS = 1;
export const MAX_DAYS = 180;
export const DAY_PRESETS = [7, 30, 90, 180];

/** Client-side check of one override; the server validates again (and checks the cross-field rules). */
export function validateParam(def: ParamDef, raw: string): string | null {
  const text = raw.trim().replace(',', '.');
  if (text === '') {
    return null;
  }
  const value = Number(text);
  if (!Number.isFinite(value)) {
    return 'Informe um número';
  }
  if (def.integer && !Number.isInteger(value)) {
    return 'Use um número inteiro';
  }
  if (def.min !== undefined && value < def.min) {
    return `Mínimo ${def.min}`;
  }
  if (def.max !== undefined && value > def.max) {
    return `Máximo ${def.max}`;
  }
  return null;
}

export function validateDays(days: number): string | null {
  if (!Number.isInteger(days)) {
    return 'Use um número inteiro de dias';
  }
  return days < MIN_DAYS || days > MAX_DAYS ? `Entre ${MIN_DAYS} e ${MAX_DAYS} dias` : null;
}

/** The overrides as numbers, only the filled-in ones. */
export function parseOverrides(raw: Record<string, string>): Record<string, number> {
  const parsed: Record<string, number> = {};
  for (const def of ALL_PARAMS) {
    const text = (raw[def.key] ?? '').trim().replace(',', '.');
    if (text !== '' && Number.isFinite(Number(text))) {
      parsed[def.key] = Number(text);
    }
  }
  return parsed;
}
