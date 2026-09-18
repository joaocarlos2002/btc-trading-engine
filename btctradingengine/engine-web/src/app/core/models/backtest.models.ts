import { IsoInstant } from './live.models';

/** The BacktestParams components the backtest form can override; any other component keeps its config value. */
export type BacktestParamValues = Partial<Record<string, number>>;

/** backtest.BacktestReport */
export interface BacktestReport {
  totalTrades: number;
  winTrades: number;
  loseTrades: number;
  winRate: number;
  totalPnL: number;
  finalEquity: number;
  averagePnL: number;
  averageReturnPercent: number;
  returnPercent: number;
  profitFactor: number;
  maxDrawdown: number;
  sharpeRatio: number;
  averageBarsPerTrade: number;
}

/** The result map of a SINGLE backtest (BacktestService.execute) */
export interface BacktestResult {
  symbol: string;
  interval: string;
  requestedDays: number;
  candleCount: number;
  rangeStart: IsoInstant;
  rangeEnd: IsoInstant;
  /** Every BacktestParams component, as used by the run */
  params: Record<string, number | boolean | string>;
  derivativesLoaded: boolean;
  sizeSplitCandles: number;
  report: BacktestReport;
}

export type BacktestJobStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED';

/** BacktestService.JobView */
export interface BacktestJob {
  id: string;
  kind: 'SINGLE' | 'SWEEP' | 'WALK_FORWARD';
  status: BacktestJobStatus;
  createdAt: IsoInstant;
  startedAt: IsoInstant | null;
  finishedAt: IsoInstant | null;
  error: string | null;
  result: BacktestResult | null;
}

/** Body of POST /api/backtests (DashboardController.BacktestJobBody) */
export interface BacktestJobRequest {
  days: number;
  sizeSplit?: boolean;
  params?: BacktestParamValues;
}

/** 400 body of the backtest endpoints */
export interface BacktestErrorBody {
  error?: string;
  errors?: string[];
}
