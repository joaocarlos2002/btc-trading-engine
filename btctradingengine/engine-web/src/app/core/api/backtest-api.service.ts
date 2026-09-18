import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { BacktestJob, BacktestJobRequest } from '../models/backtest.models';

/**
 * Backtest jobs (issue #83): submit, then poll until DONE or FAILED. 400 carries the validation errors,
 * 429 means another backtest is still running.
 */
@Injectable({ providedIn: 'root' })
export class BacktestApi {
  private readonly http = inject(HttpClient);

  submit(request: BacktestJobRequest): Observable<BacktestJob> {
    return this.http.post<BacktestJob>('/api/backtests', request);
  }

  job(id: string): Observable<BacktestJob> {
    return this.http.get<BacktestJob>(`/api/backtests/${encodeURIComponent(id)}`);
  }
}
