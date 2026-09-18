import { TestBed } from '@angular/core/testing';
import { NEVER, of, Subject } from 'rxjs';
import { DashboardApi } from '../../core/api/dashboard-api.service';
import { LiveSocket } from '../../core/live/live-socket.service';
import { Candle, LiveMessage, Position } from '../../core/models/live.models';
import { equityCurve, LiveStore, MAX_CANDLES, mergeCandles } from './live.store';

function candle(minute: number, close = 100): Candle {
  const open = new Date(Date.UTC(2026, 8, 18, 12, minute)).toISOString();
  const closeTime = new Date(Date.UTC(2026, 8, 18, 12, minute + 15)).toISOString();
  return {
    instrument: 'BTCUSDT',
    openTime: open,
    closeTime,
    open: 100,
    high: 110,
    low: 90,
    close,
    volume: 1,
    tickCount: 10,
  };
}

function trade(id: string, pnL: number, exitReason: Position['exitReason'] = 'TARGET_HIT'): Position {
  return { positionId: id, pnL, exitReason } as Position;
}

describe('mergeCandles', () => {
  it('orders by open time and lets the later copy of a candle win', () => {
    const merged = mergeCandles([candle(15), candle(0)], [candle(15, 105)]);
    expect(merged.map((c) => c.close)).toEqual([100, 105]);
  });

  it('keeps only the newest candles', () => {
    const many = Array.from({ length: MAX_CANDLES + 5 }, (_, i) => candle(i * 15));
    const merged = mergeCandles([], many);
    expect(merged).toHaveLength(MAX_CANDLES);
    expect(merged[0].openTime).toBe(many[5].openTime);
  });
});

describe('equityCurve', () => {
  it('accumulates P&L and skips failed entries, like the server stats', () => {
    expect(equityCurve([trade('a', 5), trade('b', -2, 'ORDER_FAILED'), trade('c', -1)])).toEqual([5, 4]);
  });
});

describe('LiveStore', () => {
  let store: LiveStore;
  const messages = new Subject<LiveMessage>();
  const stats = vi.fn(() => of({ totalTrades: 1, winningTrades: 1, totalPnl: 5, sharpe: 0, maxDrawdown: 0 }));

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        LiveStore,
        { provide: LiveSocket, useValue: { messages$: messages, status: () => 'open' } },
        {
          provide: DashboardApi,
          useValue: {
            candleHistory: () => of([candle(0)]),
            currentFeatures: () => of(null),
            currentPrediction: () => of(null),
            openPosition: () => of(null),
            closedTrades: () => of([]),
            stats,
            manualBuy: () => NEVER,
            manualClose: () => NEVER,
          },
        },
      ],
    });
    store = TestBed.inject(LiveStore);
  });

  it('starts from the REST snapshot', () => {
    expect(store.loading()).toBe(false);
    expect(store.candles()).toHaveLength(1);
    expect(store.price()).toBe(100);
  });

  it('applies socket messages to the matching signals', () => {
    store.apply({
      type: 'price',
      data: {
        instrument: 'BTCUSDT',
        price: 101.5,
        eventTimestamp: '2026-09-18T12:30:00.000Z',
        receiptTimestamp: '2026-09-18T12:30:00.120Z',
        quantity: 0.01,
        aggressorSide: 'BUY',
      },
    });
    expect(store.price()).toBe(101.5);
    expect(store.latencyMs()).toBe(120);

    store.apply({ type: 'candle', data: candle(15, 102) });
    expect(store.candles()).toHaveLength(2);
    expect(store.lastCandle()?.close).toBe(102);
  });

  it('takes position and history from "trades" and refreshes the stats', () => {
    stats.mockClear();
    store.apply({ type: 'trades', data: { open: null, closed: [trade('a', 5)] } });
    expect(store.position()).toBeNull();
    expect(store.closedTrades()).toHaveLength(1);
    expect(store.equity()).toEqual([5]);
    expect(stats).toHaveBeenCalledTimes(1);
  });
});
