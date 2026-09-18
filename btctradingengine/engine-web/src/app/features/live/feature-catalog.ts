import { formatCompact, formatNumber, formatPercent, isNumber, Tone, toneOf } from '../../core/format/format';
import { FeatureVector } from '../../core/models/live.models';

/** Every indicator of the FeatureVector, flattened: the groups are a transport detail. */
export type FlatFeatures = Record<string, number | null | undefined>;

export function flattenFeatures(features: FeatureVector): FlatFeatures {
  return {
    ...features.core,
    ...features.regime,
    ...features.context,
    ...features.flow,
    ...features.priceAction,
    ...features.deriv,
  } as unknown as FlatFeatures;
}

type Format = 'number' | 'percent' | 'fraction-percent' | 'integer' | 'signed-integer' | 'compact';

export interface FeatureDef {
  key: string;
  label: string;
  /** Unit or range shown under the value */
  hint: string;
  /** What the indicator measures and how to read it */
  tip: string;
  format: Format;
  digits?: number;
  /** Signed values read as bullish/bearish: colored and prefixed with + or − */
  signed?: boolean;
  /** Reads the value in words (the meaning never depends on color alone) */
  note?: (value: number) => string;
}

export interface FeatureGroup {
  id: string;
  label: string;
  description: string;
  items: FeatureDef[];
}

const trendNote = (value: number) => (value > 0 ? 'Ascendente' : value < 0 ? 'Descendente' : 'Sem dados');
const zone = (low: number, high: number) => (value: number) =>
  value >= high ? 'Sobrecomprado' : value <= low ? 'Sobrevendido' : 'Neutro';

export const FEATURE_GROUPS: FeatureGroup[] = [
  {
    id: 'trend',
    label: 'Tendência e momentum',
    description: 'Direção e força do movimento recente',
    items: [
      { key: 'returnPct1m', label: 'Retorno da vela', hint: '%', format: 'percent', digits: 3, signed: true,
        tip: 'Variação percentual entre abertura e fechamento da vela. Positivo = subiu; mede o momentum de curtíssimo prazo.' },
      { key: 'smaDistance', label: 'Distância da SMA', hint: '% do preço', format: 'percent', digits: 3, signed: true,
        tip: 'Distância do preço até a média móvel simples. Positivo = preço acima da média (viés de alta).' },
      { key: 'emaDistance', label: 'Distância da EMA', hint: '% do preço', format: 'percent', digits: 3, signed: true,
        tip: 'Distância do preço até a média móvel exponencial, que reage mais rápido que a SMA.' },
      { key: 'rsiValue', label: 'RSI 14', hint: '0 – 100', format: 'number', digits: 1, note: zone(30, 70),
        tip: 'Índice de Força Relativa. Acima de 70 = sobrecomprado (possível correção); abaixo de 30 = sobrevendido (possível repique).' },
      { key: 'macdValue', label: 'MACD', hint: 'EMA 12 − EMA 26', format: 'number', digits: 2, signed: true,
        tip: 'Diferença entre as EMAs de 12 e 26 períodos. Positivo = momentum de alta.' },
      { key: 'macdSignal', label: 'Sinal do MACD', hint: 'média do MACD', format: 'number', digits: 2, signed: true,
        tip: 'Média móvel do próprio MACD. O cruzamento entre MACD e sinal indica reversão de momentum.' },
      { key: 'adx', label: 'ADX', hint: 'força da tendência', format: 'number', digits: 1,
        note: (v) => (v >= 25 ? 'Em tendência' : v < 20 ? 'Lateral' : 'Indefinido'),
        tip: 'Mede a força da tendência, não a direção. Abaixo de 20 = mercado lateral (sinais de rompimento não valem); acima de 25 = tendência (sinais de reversão à média não valem).' },
      { key: 'emaSlope', label: 'Inclinação da EMA', hint: '% no período', format: 'percent', digits: 3, signed: true,
        tip: 'Variação percentual da EMA em indicator.ema.slope.periods velas. Confirma a direção da tendência no regime.' },
    ],
  },
  {
    id: 'volatility',
    label: 'Volatilidade e regime',
    description: 'Quanto o preço oscila e em que regime o mercado está',
    items: [
      { key: 'volatility5m', label: 'Volatilidade curta', hint: 'desvio-padrão', format: 'number', digits: 4,
        tip: 'Desvio-padrão dos retornos das últimas velas (janela curta). Mede o quanto o preço está agitado.' },
      { key: 'volatility20m', label: 'Volatilidade longa', hint: 'desvio-padrão', format: 'number', digits: 4,
        tip: 'Desvio-padrão dos retornos numa janela maior: visão mais ampla de risco.' },
      { key: 'atrValue', label: 'ATR 14', hint: 'amplitude média', format: 'number', digits: 2,
        tip: 'Average True Range: amplitude real média das velas. Mede volatilidade e ajuda a dimensionar stops.' },
      { key: 'atrPercent', label: 'ATR %', hint: '% do preço', format: 'percent', digits: 3,
        tip: 'ATR em percentual do preço. Volatilidade baixa = pouco movimento esperado, sinais menos confiáveis.' },
      { key: 'bbWidth', label: 'Largura das Bollinger', hint: '% da banda média', format: 'percent', digits: 3,
        tip: 'Largura das Bandas de Bollinger. Valor baixo = compressão (squeeze), que costuma anteceder movimento.' },
      { key: 'plusDi', label: '+DI', hint: '0 – 100', format: 'number', digits: 1,
        tip: 'Indicador direcional positivo do ADX: pressão compradora.' },
      { key: 'minusDi', label: '−DI', hint: '0 – 100', format: 'number', digits: 1,
        tip: 'Indicador direcional negativo do ADX: pressão vendedora.' },
    ],
  },
  {
    id: 'flow',
    label: 'Fluxo',
    description: 'Quem está agredindo o livro e com que tamanho',
    items: [
      { key: 'volumeRatio', label: 'Volume relativo', hint: '× média', format: 'number', digits: 2,
        tip: 'Volume da vela dividido pela média. Acima de 1 = volume acima do normal, confirma o movimento.' },
      { key: 'mfi', label: 'MFI', hint: '0 – 100', format: 'number', digits: 1, note: zone(20, 80),
        tip: 'Money Flow Index: como o RSI, mas ponderado por volume. Abaixo de 20 = sobrevendido, acima de 80 = sobrecomprado.' },
      { key: 'deltaRatio', label: 'Delta', hint: '−1 a 1', format: 'number', digits: 3, signed: true,
        tip: '(Volume comprador agressor − vendedor agressor) ÷ volume da vela. +1 = só compra a mercado; −1 = só venda.' },
      { key: 'cvd', label: 'CVD', hint: 'BTC na janela', format: 'number', digits: 2, signed: true,
        tip: 'Cumulative Volume Delta: soma do delta na janela de N velas. Subindo com o preço confirma; divergindo sugere exaustão.' },
      { key: 'cvdRatio', label: 'CVD relativo', hint: '−1 a 1', format: 'number', digits: 3, signed: true,
        tip: 'CVD dividido pelo volume agressor da janela: comparável entre mercado calmo e agitado.' },
      { key: 'largeCvd', label: 'CVD de grandes', hint: 'BTC, trades grandes', format: 'number', digits: 2, signed: true,
        tip: 'CVD só das agressões grandes (≥ feature.flow.large.trade.notional). Ao vivo sempre; no backtest só com sizeSplit.' },
      { key: 'largeVolumeShare', label: 'Fatia de grandes', hint: '0 – 1', format: 'number', digits: 3,
        tip: 'Parcela do volume agressor da janela que veio de trades grandes.' },
      { key: 'vpin', label: 'VPIN', hint: '0 – 1', format: 'number', digits: 3,
        tip: 'Desequilíbrio médio entre compra e venda agressora em baldes de volume. Perto de 1 = fluxo tóxico. Com prediction.vpin.filter.enabled, a partir de prediction.vpin.high bloqueia novas entradas. 0 = aquecendo.' },
      { key: 'absorption', label: 'Absorção', hint: '−1 a 1', format: 'number', digits: 3, signed: true,
        tip: 'Agressão forte de um lado sem o preço andar. Positivo = venda absorvida (viés de alta); negativo = compra absorvida (viés de baixa).' },
      { key: 'absorptionSum', label: 'Absorção acumulada', hint: 'na janela', format: 'number', digits: 3, signed: true,
        tip: 'Soma da absorção nas últimas feature.absorption.window velas: absorção repetida no mesmo lado pesa mais.' },
      { key: 'orderBookImbalance', label: 'Desequilíbrio do book', hint: '0 – 1 · só ao vivo', format: 'number', digits: 3,
        note: (v) => (v > 0.55 ? 'Mais compradores' : v < 0.45 ? 'Mais vendedores' : 'Equilibrado'),
        tip: 'Volume de compra ÷ (compra + venda) nos níveis lidos do order book. 0,5 = equilibrado. Filtro de execução, não voto; sem histórico no backtest.' },
    ],
  },
  {
    id: 'price-action',
    label: 'Price action',
    description: 'Anatomia das velas, estrutura e níveis',
    items: [
      { key: 'bodyRatio', label: 'Corpo', hint: '0 – 1 do range', format: 'number', digits: 3,
        tip: '|fechamento − abertura| em fração do range. Perto de 1 = vela de força; perto de 0 = indecisão (doji).' },
      { key: 'upperWickRatio', label: 'Pavio superior', hint: '0 – 1 do range', format: 'number', digits: 3,
        tip: 'Sombra superior em fração do range. Longa = rejeição de preços altos (pressão vendedora).' },
      { key: 'lowerWickRatio', label: 'Pavio inferior', hint: '0 – 1 do range', format: 'number', digits: 3,
        tip: 'Sombra inferior em fração do range. Longa = rejeição de preços baixos (pressão compradora).' },
      { key: 'highLowRatio', label: 'Amplitude', hint: '% da mínima', format: 'percent', digits: 3,
        tip: '(máxima − mínima) ÷ mínima: o tamanho da vela em percentual.' },
      { key: 'closePosition', label: 'Posição do fechamento', hint: '0 – 1', format: 'number', digits: 3,
        tip: '(fechamento − mínima) ÷ (máxima − mínima). Perto de 1 = fechou no topo (força).' },
      { key: 'candleStreak', label: 'Sequência', hint: 'velas', format: 'signed-integer', signed: true,
        tip: 'Velas seguidas na mesma direção: +3 = três de alta, −2 = duas de baixa. Doji zera a contagem.' },
      { key: 'previousCandleBreak', label: 'Rompimento anterior', hint: '−1 · 0 · +1', format: 'signed-integer', signed: true,
        note: (v) => (v > 0 ? 'Acima da máxima' : v < 0 ? 'Abaixo da mínima' : 'Dentro'),
        tip: '+1 = fechou acima da máxima da vela anterior; −1 = abaixo da mínima; 0 = dentro.' },
      { key: 'recentHighDistance', label: 'Até a máxima recente', hint: '%', format: 'percent', digits: 3, signed: true,
        tip: 'Distância até a máxima das últimas N velas (sem a atual). Positivo = rompeu a máxima.' },
      { key: 'recentLowDistance', label: 'Até a mínima recente', hint: '%', format: 'percent', digits: 3, signed: true,
        tip: 'Distância até a mínima das últimas N velas (sem a atual). Negativo = perdeu a mínima.' },
      { key: 'swingHighTrend', label: 'Topos', hint: '−1 · 0 · +1', format: 'signed-integer', signed: true, note: trendNote,
        tip: 'Compara os dois últimos topos (pivôs): +1 = topo ascendente, −1 = descendente. Confirmado com atraso de swing.strength velas.' },
      { key: 'swingLowTrend', label: 'Fundos', hint: '−1 · 0 · +1', format: 'signed-integer', signed: true, note: trendNote,
        tip: 'Compara os dois últimos fundos: +1 = fundo ascendente, −1 = descendente. Topos e fundos ascendentes = tendência de alta.' },
      { key: 'supportDistance', label: 'Suporte', hint: '% abaixo', format: 'percent', digits: 3,
        tip: 'Distância até o suporte mais próximo (pivô mais alto abaixo do preço). 0 = nenhum suporte na janela.' },
      { key: 'resistanceDistance', label: 'Resistência', hint: '% acima', format: 'percent', digits: 3,
        tip: 'Distância até a resistência mais próxima (pivô mais baixo acima do preço). 0 = nenhuma na janela.' },
    ],
  },
  {
    id: 'derivatives',
    label: 'Derivativos',
    description: 'Posicionamento no perpétuo BTCUSDT',
    items: [
      { key: 'fundingRate', label: 'Funding', hint: 'por 8 h', format: 'fraction-percent', digits: 4, signed: true,
        tip: 'Último funding liquidado do perpétuo. Positivo = longs pagam shorts (mercado alavancado comprado).' },
      { key: 'basisPercent', label: 'Basis', hint: '% perp vs spot', format: 'percent', digits: 4, signed: true,
        tip: '(Perpétuo − spot) ÷ spot. Positivo = futuros com prêmio (demanda alavancada de compra).' },
      { key: 'openInterest', label: 'Open interest', hint: 'BTC', format: 'compact',
        tip: 'Contratos abertos no perpétuo, em BTC. No backtest vem dos dumps diários da Binance.' },
      { key: 'openInterestChangePercent', label: 'Variação do OI', hint: '%', format: 'percent', digits: 2, signed: true,
        tip: 'Variação do open interest contra derivatives.open.interest.change.minutes atrás. Subindo com o preço = dinheiro novo entrando.' },
      { key: 'longShortRatio', label: 'Long/short', hint: 'contas', format: 'number', digits: 3,
        tip: 'Contas compradas ÷ vendidas no perpétuo. Acima de 1 = mais contas long; extremos indicam posicionamento lotado.' },
    ],
  },
  {
    id: 'context',
    label: 'Contexto',
    description: 'Onde o preço está em relação à sessão e ao canal',
    items: [
      { key: 'vwapDistance', label: 'Distância do VWAP', hint: '% vs sessão', format: 'percent', digits: 3, signed: true,
        tip: 'Distância do preço até o VWAP da sessão UTC. Positivo = acima do custo médio do dia.' },
      { key: 'bbPercentB', label: 'Bollinger %B', hint: '0 – 1', format: 'number', digits: 3,
        tip: 'Posição do preço dentro das Bandas de Bollinger: 0 = banda inferior, 1 = superior. Só confirmação.' },
      { key: 'donchianPosition', label: 'Posição no Donchian', hint: '0 – 1', format: 'number', digits: 3,
        tip: 'Posição do preço no canal de Donchian. 1 = rompendo o topo do canal; 0 = rompendo o fundo.' },
      { key: 'hourOfDay', label: 'Hora', hint: 'UTC', format: 'integer',
        tip: 'Hora do dia em UTC: identifica as sessões mais voláteis (Ásia, Europa, EUA).' },
      { key: 'dayOfWeek', label: 'Dia da semana', hint: '1 = segunda', format: 'integer',
        note: (v) => ['Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado', 'Domingo'][v - 1] ?? '',
        tip: 'Dia da semana (1 = segunda, 7 = domingo). Fins de semana costumam ter menos volume.' },
    ],
  },
];

export interface FeatureReading {
  def: FeatureDef;
  value: string;
  tone: Tone;
  note: string;
}

export function readFeature(def: FeatureDef, value: number | null | undefined): FeatureReading {
  const number = isNumber(value) ? value : null;
  let text: string;
  switch (def.format) {
    case 'percent':
      text = formatPercent(number, def.digits ?? 2, { signed: def.signed });
      break;
    case 'fraction-percent':
      text = formatPercent(number, def.digits ?? 2, { fraction: true, signed: def.signed });
      break;
    case 'integer':
      text = formatNumber(number, 0);
      break;
    case 'signed-integer':
      text = formatNumber(number, 0, true);
      break;
    case 'compact':
      text = formatCompact(number);
      break;
    default:
      text = formatNumber(number, def.digits ?? 2, def.signed ?? false);
  }
  return {
    def,
    value: text,
    tone: def.signed ? toneOf(number) : 'neutral',
    note: number !== null && def.note ? def.note(number) : '',
  };
}
