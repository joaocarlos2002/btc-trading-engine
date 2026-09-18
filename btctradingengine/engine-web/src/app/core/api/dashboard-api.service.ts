import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Candle,
  FeatureVector,
  ManualActionResult,
  Position,
  Prediction,
  Stats,
} from '../models/live.models';

/**
 * REST side of the live dashboard (DashboardController). The "current" endpoints answer 204 before the
 * first value exists, which HttpClient hands over as null.
 */
@Injectable({ providedIn: 'root' })
export class DashboardApi {
  private readonly http = inject(HttpClient);

  candleHistory(limit: number): Observable<Candle[]> {
    return this.http.get<Candle[]>('/api/candles/history', { params: { limit } });
  }

  currentFeatures(): Observable<FeatureVector | null> {
    return this.http.get<FeatureVector | null>('/api/metrics/current');
  }

  currentPrediction(): Observable<Prediction | null> {
    return this.http.get<Prediction | null>('/api/prediction/current');
  }

  openPosition(): Observable<Position | null> {
    return this.http.get<Position | null>('/api/trades/open');
  }

  closedTrades(limit: number): Observable<Position[]> {
    return this.http.get<Position[]>('/api/trades/closed', { params: { limit } });
  }

  stats(): Observable<Stats> {
    return this.http.get<Stats>('/api/stats');
  }

  /** 200 or 400 both carry a ManualActionResult; 403 means the login lacks the TRADER role. */
  manualBuy(): Observable<ManualActionResult> {
    return this.http.post<ManualActionResult>('/api/trades/manual/buy', null);
  }

  manualClose(): Observable<ManualActionResult> {
    return this.http.post<ManualActionResult>('/api/trades/manual/close', null);
  }
}
