import { HttpErrorResponse } from '@angular/common/http';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  catchError,
  firstValueFrom,
  forkJoin,
  map,
  Observable,
  of,
  Subject,
  switchMap,
  EMPTY,
} from 'rxjs';
import { DashboardApi } from '../../core/api/dashboard-api.service';
import { millisBetween } from '../../core/format/format';
import { LiveSocket } from '../../core/live/live-socket.service';
import {
  Candle,
  ExitReason,
  FeatureVector,
  LiveMessage,
  LiveMessageType,
  Position,
  Prediction,
  PriceTick,
  Stats,
} from '../../core/models/live.models';

export const MAX_CANDLES = 300;
export const MAX_TICKS = 90;
export const MAX_TRADES = 50;

/** Same filter as ExitReason.isPerformanceTrade: a failed entry is not a trade. */
const NON_TRADES: ReadonlySet<ExitReason> = new Set(['ORDER_FAILED', 'VALIDATION_FAILED', 'ERROR']);

export function isPerformanceTrade(position: Position): boolean {
  return !position.exitReason || !NON_TRADES.has(position.exitReason);
}

/** Merges candles by open time (a later copy wins), oldest first, keeping the newest `max`. */
export function mergeCandles(current: Candle[], incoming: Candle[], max = MAX_CANDLES): Candle[] {
  const byOpen = new Map<string, Candle>();
  for (const candle of [...current, ...incoming]) {
    byOpen.set(candle.openTime, candle);
  }
  return [...byOpen.values()]
    .sort((a, b) => Date.parse(a.openTime) - Date.parse(b.openTime))
    .slice(-max);
}

/** 1 min · 15 min · 1 h, from a candle's span (Binance closes a candle 1 ms before the next one opens). */
export function intervalLabel(spanMillis: number): string | null {
  const minutes = Math.round(spanMillis / 60_000);
  if (!Number.isFinite(minutes) || minutes <= 0) {
    return null;
  }
  return minutes % 60 === 0 ? `${minutes / 60} h` : `${minutes} min`;
}

/** Cumulative P&L after each performance trade, in order. */
export function equityCurve(closed: Position[]): number[] {
  let total = 0;
  return closed.filter(isPerformanceTrade).map((trade) => (total += trade.pnL || 0));
}

export type ManualAction = 'buy' | 'close';

export interface ManualOutcome {
  ok: boolean;
  message: string;
  /** The login lacks the TRADER role */
  forbidden?: boolean;
}

/**
 * State of the live screen. The REST endpoints give the first picture; /ws/live keeps it current.
 * Socket messages are coalesced and applied once per animation frame, so a burst of ticks costs one
 * render, and each panel reads only the signals it shows.
 *
 * Provided by the live page, so the socket connects on entering it and closes on leaving.
 */
@Injectable()
export class LiveStore {
  private readonly api = inject(DashboardApi);
  private readonly socket = inject(LiveSocket);
  private readonly destroyRef = inject(DestroyRef);

  readonly tick = signal<PriceTick | null>(null);
  readonly tickPrices = signal<number[]>([]);
  readonly candles = signal<Candle[]>([]);
  readonly features = signal<FeatureVector | null>(null);
  readonly prediction = signal<Prediction | null>(null);
  readonly position = signal<Position | null>(null);
  readonly closedTrades = signal<Position[]>([]);
  readonly stats = signal<Stats | null>(null);

  readonly loading = signal(true);
  /** Nothing could be loaded: the server is down or unreachable */
  readonly loadFailed = signal(false);
  readonly pendingAction = signal<ManualAction | null>(null);

  readonly connection = this.socket.status;
  readonly lastCandle = computed(() => this.candles().at(-1) ?? null);
  readonly price = computed(() => this.tick()?.price ?? this.lastCandle()?.close ?? null);
  readonly instrument = computed(
    () => this.tick()?.instrument ?? this.lastCandle()?.instrument ?? this.prediction()?.instrument ?? 'BTCUSDT',
  );
  readonly latencyMs = computed(() => millisBetween(this.tick()?.eventTimestamp, this.tick()?.receiptTimestamp));
  readonly lastUpdate = computed(
    () => this.tick()?.receiptTimestamp ?? this.features()?.timestamp ?? this.lastCandle()?.closeTime ?? null,
  );
  readonly equity = computed(() => equityCurve(this.closedTrades()));
  /** The candle size the engine runs with (market.interval.seconds), read from the candles themselves */
  readonly intervalLabel = computed(() => {
    const candle = this.lastCandle();
    return candle ? intervalLabel(Date.parse(candle.closeTime) - Date.parse(candle.openTime)) : null;
  });

  /** Types already received from the socket: newer than anything the initial REST load returns. */
  private readonly live = new Set<LiveMessageType>();
  private readonly pending = new Map<LiveMessageType, LiveMessage>();
  private readonly pendingTicks: number[] = [];
  private frame = 0;
  private readonly statsRefresh = new Subject<void>();

  constructor() {
    this.socket.messages$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((message) => this.receive(message));
    this.statsRefresh
      .pipe(
        switchMap(() => this.api.stats().pipe(catchError(() => EMPTY))),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((stats) => this.stats.set(stats));
    this.destroyRef.onDestroy(() => cancelAnimationFrame(this.frame));
    this.load();
  }

  /** First picture from REST. Each call fails on its own, so one missing endpoint does not blank the page. */
  load(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    const safe = <T>(request: Observable<T>) =>
      request.pipe(
        map((value) => ({ ok: true as const, value })),
        catchError(() => of({ ok: false as const, value: null })),
      );
    forkJoin({
      candles: safe(this.api.candleHistory(100)),
      features: safe(this.api.currentFeatures()),
      prediction: safe(this.api.currentPrediction()),
      position: safe(this.api.openPosition()),
      closed: safe(this.api.closedTrades(MAX_TRADES)),
      stats: safe(this.api.stats()),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        if (result.candles.value) {
          this.candles.update((current) => mergeCandles(result.candles.value ?? [], current));
        }
        if (!this.live.has('metrics') && result.features.value) this.features.set(result.features.value);
        if (!this.live.has('prediction') && result.prediction.value) this.prediction.set(result.prediction.value);
        if (!this.live.has('position') && !this.live.has('trades') && result.position.ok) {
          this.position.set(result.position.value);
        }
        if (!this.live.has('trades') && result.closed.value) this.closedTrades.set(result.closed.value);
        if (result.stats.value) this.stats.set(result.stats.value);
        this.loadFailed.set(Object.values(result).every((part) => !part.ok));
        this.loading.set(false);
      });
  }

  /** Sends a manual order. The position itself updates through the socket's "trades" message. */
  async manual(action: ManualAction): Promise<ManualOutcome> {
    this.pendingAction.set(action);
    try {
      const request = action === 'buy' ? this.api.manualBuy() : this.api.manualClose();
      const result = await firstValueFrom(request);
      return { ok: result.success, message: result.message };
    } catch (error) {
      if (error instanceof HttpErrorResponse) {
        if (error.status === 403) {
          return { ok: false, forbidden: true, message: 'Sua conta não tem o perfil TRADER.' };
        }
        const body = error.error as { message?: string } | null;
        if (body?.message) {
          return { ok: false, message: body.message };
        }
      }
      return { ok: false, message: 'Não foi possível falar com o servidor.' };
    } finally {
      this.pendingAction.set(null);
    }
  }

  /** Applies one message right away. The socket path goes through receive(), which batches per frame. */
  apply(message: LiveMessage): void {
    this.live.add(message.type);
    switch (message.type) {
      case 'price':
        this.tick.set(message.data);
        break;
      case 'candle':
        this.candles.update((current) => mergeCandles(current, [message.data]));
        break;
      case 'metrics':
        this.features.set(message.data);
        break;
      case 'prediction':
        this.prediction.set(message.data);
        break;
      case 'position':
        this.position.set(message.data);
        break;
      case 'trades':
        this.position.set(message.data.open);
        this.closedTrades.set(message.data.closed ?? []);
        this.statsRefresh.next();
        break;
    }
  }

  private receive(message: LiveMessage): void {
    if (message.type === 'price' && Number.isFinite(message.data.price)) {
      this.pendingTicks.push(message.data.price);
      // A hidden tab gets no animation frames: keep only what the sparkline can show
      if (this.pendingTicks.length > MAX_TICKS) {
        this.pendingTicks.shift();
      }
    }
    // A closed candle must never be dropped for a newer one of the same type in the same frame
    if (message.type === 'candle' && this.pending.has('candle')) {
      this.apply(this.pending.get('candle')!);
    }
    this.pending.set(message.type, message);
    if (!this.frame) {
      this.frame = requestAnimationFrame(() => this.flush());
    }
  }

  private flush(): void {
    this.frame = 0;
    for (const message of this.pending.values()) {
      this.apply(message);
    }
    this.pending.clear();
    if (this.pendingTicks.length) {
      const ticks = this.pendingTicks.splice(0);
      this.tickPrices.update((current) => [...current, ...ticks].slice(-MAX_TICKS));
    }
  }
}
