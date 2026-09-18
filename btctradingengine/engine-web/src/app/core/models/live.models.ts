// Contracts of the live dashboard, mirroring the Java records serialized by Spring's ObjectMapper.
// BigDecimal arrives as a JSON number and Instant as an ISO-8601 string (issue #87).

export type IsoInstant = string;

export type TradeSignal = 'BUY' | 'SELL' | 'HOLD';

export type MarketRegime = 'UNKNOWN' | 'SQUEEZE' | 'RANGE' | 'TRANSITION' | 'TREND_UP' | 'TREND_DOWN';

export type PositionState = 'PENDING_ENTRY' | 'OPEN' | 'EXIT_PENDING' | 'CLOSED' | 'FAILED';

export type ExitReason =
  | 'TARGET_HIT'
  | 'STOP_LOSS'
  | 'SIGNAL_REVERSAL'
  | 'MANUAL_CLOSE'
  | 'ORDER_FAILED'
  | 'VALIDATION_FAILED'
  | 'ERROR';

/** model.NormalizedPriceEvent */
export interface PriceTick {
  instrument: string;
  price: number;
  eventTimestamp: IsoInstant | null;
  receiptTimestamp: IsoInstant | null;
  quantity: number | null;
  aggressorSide: 'BUY' | 'SELL' | 'UNKNOWN' | null;
}

/** model.CandleEvent */
export interface Candle {
  instrument: string;
  openTime: IsoInstant;
  closeTime: IsoInstant;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  tickCount: number;
}

/** feature.CoreFeatures */
export interface CoreFeatures {
  returnPct1m: number;
  volatility5m: number;
  volatility20m: number;
  smaDistance: number;
  emaDistance: number;
  rsiValue: number;
  macdValue: number;
  macdSignal: number;
  atrValue: number;
  volumeRatio: number;
  highLowRatio: number;
  closePosition: number;
  hourOfDay: number;
  dayOfWeek: number;
}

/** feature.RegimeFeatures */
export interface RegimeFeatures {
  adx: number;
  plusDi: number;
  minusDi: number;
  atrPercent: number;
  bbWidth: number;
  emaSlope: number;
}

/** feature.ContextFeatures */
export interface ContextFeatures {
  vwap: number;
  vwapDistance: number;
  bbPercentB: number;
  donchianUpper: number;
  donchianLower: number;
  donchianPosition: number;
}

/** feature.FlowFeatures */
export interface FlowFeatures {
  mfi: number;
  volumeDelta: number;
  deltaRatio: number;
  cvd: number;
  cvdRatio: number;
  largeCvd: number;
  largeVolumeShare: number;
  vpin: number;
  absorption: number;
  absorptionSum: number;
  /** null when the order book is not being read */
  orderBookImbalance: number | null;
}

/** feature.DerivFeatures: every field is null while derivatives data is unavailable */
export interface DerivFeatures {
  openInterest: number | null;
  fundingRate: number | null;
  basisPercent: number | null;
  openInterestChangePercent: number | null;
  longShortRatio: number | null;
}

/** feature.PriceActionFeatures */
export interface PriceActionFeatures {
  bodyRatio: number;
  upperWickRatio: number;
  lowerWickRatio: number;
  candleStreak: number;
  previousCandleBreak: number;
  recentHighDistance: number;
  recentLowDistance: number;
  swingHighTrend: number;
  swingLowTrend: number;
  supportDistance: number;
  resistanceDistance: number;
}

/** feature.FeatureVector: the indicators grouped by role */
export interface FeatureVector {
  instrument: string;
  timestamp: IsoInstant;
  core: CoreFeatures;
  regime: RegimeFeatures;
  context: ContextFeatures;
  flow: FlowFeatures;
  deriv: DerivFeatures;
  priceAction: PriceActionFeatures;
  price: number;
  tickCount: number;
}

/** prediction.PredictionVector */
export interface Prediction {
  instrument: string;
  timestamp: IsoInstant;
  signal: TradeSignal;
  probabilityUp: number;
  probabilityDown: number;
  confidence: number;
  price: number;
  modelVersion: string;
  marketRegime: MarketRegime;
  /** false while filter rules or an entry guard (VPIN, order book) block new entries; exits still apply */
  entryAllowed: boolean;
  reason: string | null;
}

/** trading.Position. pnL is per unit (price points), not USDT. */
export interface Position {
  positionId: string;
  signal: TradeSignal;
  entryPrice: number;
  entryTime: IsoInstant;
  currentPrice: number | null;
  lastUpdateTime: IsoInstant | null;
  exitPrice: number | null;
  exitTime: IsoInstant | null;
  exitReason: ExitReason | null;
  status: 'OPEN' | 'CLOSED';
  state: PositionState;
  open: boolean;
  targetPercent: number;
  stopLossPercent: number;
  quantity: number | null;
  targetPrice: number | null;
  stopLossPrice: number | null;
  pnL: number;
  pnLPercent: number;
}

/** DashboardState.Stats. totalPnl is in price points; maxDrawdown in percent. */
export interface Stats {
  totalTrades: number;
  winningTrades: number;
  totalPnl: number;
  sharpe: number;
  maxDrawdown: number;
}

/** DashboardState.TradesPayload: sent whenever positions change (issue #85) */
export interface TradesPayload {
  open: Position | null;
  closed: Position[];
}

/** DashboardState.ManualBuyResult */
export interface ManualActionResult {
  success: boolean;
  message: string;
}

/** Messages of /ws/live: {type, data}, coalesced per type every 50 ms by DashboardState */
export type LiveMessage =
  | { type: 'price'; data: PriceTick }
  | { type: 'candle'; data: Candle }
  | { type: 'metrics'; data: FeatureVector }
  | { type: 'prediction'; data: Prediction }
  | { type: 'position'; data: Position }
  | { type: 'trades'; data: TradesPayload };

export type LiveMessageType = LiveMessage['type'];

export type LiveData<T extends LiveMessageType> = Extract<LiveMessage, { type: T }>['data'];
