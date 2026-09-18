import { HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Subscription, switchMap, takeWhile, timer } from 'rxjs';
import { BacktestApi } from '../../core/api/backtest-api.service';
import {
  BacktestErrorBody,
  BacktestJob,
  BacktestJobRequest,
  BacktestResult,
} from '../../core/models/backtest.models';

const POLL_MS = 1500;
const MAX_HISTORY = 6;

export type RunPhase = 'idle' | 'submitting' | 'running' | 'done' | 'failed';

export interface RunProblem {
  kind: 'invalid' | 'busy' | 'failed' | 'network';
  message: string;
  errors: string[];
}

export interface RunRecord {
  id: string;
  days: number;
  overrides: Record<string, number>;
  finishedAt: string;
  result: BacktestResult;
}

/**
 * Runs backtests as jobs (issue #83) and polls them until they finish. Kept at the root, so a run keeps
 * going (and its result stays) while the user looks at another screen.
 */
@Injectable({ providedIn: 'root' })
export class BacktestStore {
  private readonly api = inject(BacktestApi);

  /** The form, as the user left it */
  readonly draftDays = signal(30);
  readonly draftOverrides = signal<Record<string, string>>({});

  readonly phase = signal<RunPhase>('idle');
  readonly job = signal<BacktestJob | null>(null);
  readonly problem = signal<RunProblem | null>(null);
  readonly history = signal<RunRecord[]>([]);
  readonly selectedId = signal<string | null>(null);
  /** The request of the run in progress (or the last one) */
  readonly request = signal<BacktestJobRequest | null>(null);

  readonly busy = computed(() => this.phase() === 'submitting' || this.phase() === 'running');
  readonly selected = computed(() => {
    const id = this.selectedId();
    return this.history().find((run) => run.id === id) ?? this.history()[0] ?? null;
  });

  private polling: Subscription | null = null;

  run(request: BacktestJobRequest): void {
    this.polling?.unsubscribe();
    this.phase.set('submitting');
    this.problem.set(null);
    this.request.set(request);
    this.polling = this.api
      .submit(request)
      .pipe(
        switchMap((job) => {
          this.job.set(job);
          this.phase.set('running');
          return timer(POLL_MS, POLL_MS).pipe(
            switchMap(() => this.api.job(job.id)),
            takeWhile((current) => current.status !== 'DONE' && current.status !== 'FAILED', true),
          );
        }),
      )
      .subscribe({
        next: (job) => this.onJob(job, request),
        error: (error: unknown) => this.onError(error),
      });
  }

  select(id: string): void {
    this.selectedId.set(id);
  }

  dismissProblem(): void {
    this.problem.set(null);
    if (this.phase() === 'failed') {
      this.phase.set(this.history().length ? 'done' : 'idle');
    }
  }

  private onJob(job: BacktestJob, request: BacktestJobRequest): void {
    this.job.set(job);
    if (job.status === 'DONE' && job.result) {
      const record: RunRecord = {
        id: job.id,
        days: request.days,
        overrides: (request.params ?? {}) as Record<string, number>,
        finishedAt: job.finishedAt ?? new Date().toISOString(),
        result: job.result,
      };
      this.history.update((runs) => [record, ...runs.filter((run) => run.id !== job.id)].slice(0, MAX_HISTORY));
      this.selectedId.set(job.id);
      this.phase.set('done');
    } else if (job.status === 'FAILED') {
      this.problem.set({ kind: 'failed', message: job.error ?? 'O backtest falhou.', errors: [] });
      this.phase.set('failed');
    }
  }

  private onError(error: unknown): void {
    this.phase.set('failed');
    if (!(error instanceof HttpErrorResponse)) {
      this.problem.set({ kind: 'network', message: 'Erro inesperado ao rodar o backtest.', errors: [] });
      return;
    }
    const body = (error.error ?? {}) as BacktestErrorBody;
    if (error.status === 400) {
      this.problem.set({
        kind: 'invalid',
        message: 'Alguns parâmetros não são válidos.',
        errors: body.errors ?? (body.error ? [body.error] : []),
      });
    } else if (error.status === 429) {
      this.problem.set({
        kind: 'busy',
        message: 'Outro backtest já está rodando. Tente de novo quando ele terminar.',
        errors: [],
      });
    } else if (error.status === 0) {
      this.problem.set({ kind: 'network', message: 'Sem resposta do servidor. Verifique a conexão.', errors: [] });
    } else {
      this.problem.set({ kind: 'failed', message: body.error ?? `O servidor respondeu ${error.status}.`, errors: [] });
    }
  }
}
