package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.TradeFlow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #85: the backtest kline cache is bounded by entries and total candles, and keeps its TTL. */
class BinanceKlineCacheTest {

    private static final Instant T0 = Instant.parse("2026-09-16T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);

    private static List<CandleEvent> candles(int count) {
        List<CandleEvent> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant open = T0.plusSeconds(60L * i);
            candles.add(new CandleEvent("BTCUSDT", open, open.plusSeconds(59), BigDecimal.ONE, BigDecimal.ONE,
                    BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1,
                    TradeFlow.fromKline(BigDecimal.ONE, BigDecimal.ZERO)));
        }
        return candles;
    }

    @Test
    void evictsLeastRecentlyUsedBeyondMaxEntries() {
        BinanceKlineClient.CandleCache cache = new BinanceKlineClient.CandleCache(TTL, 2, 1_000);
        cache.put("a", candles(1), T0);
        cache.put("b", candles(1), T0);
        assertNotNull(cache.get("a", T0)); // a is now more recently used than b
        cache.put("c", candles(1), T0);

        assertEquals(2, cache.size());
        assertTrue(cache.contains("a"));
        assertFalse(cache.contains("b"));
        assertTrue(cache.contains("c"));
    }

    @Test
    void evictsBeyondMaxTotalCandles() {
        BinanceKlineClient.CandleCache cache = new BinanceKlineClient.CandleCache(TTL, 10, 100);
        cache.put("a", candles(40), T0);
        cache.put("b", candles(40), T0);
        cache.put("c", candles(40), T0);

        assertFalse(cache.contains("a"));
        assertEquals(80, cache.totalCandles());

        // A window larger than the whole budget is not cached and does not evict the others
        cache.put("huge", candles(101), T0);
        assertFalse(cache.contains("huge"));
        assertEquals(2, cache.size());
    }

    @Test
    void entriesExpireAfterTheTtl() {
        BinanceKlineClient.CandleCache cache = new BinanceKlineClient.CandleCache(TTL, 10, 1_000);
        cache.put("a", candles(10), T0);

        assertNotNull(cache.get("a", T0.plus(TTL).minusSeconds(1)));
        assertNull(cache.get("a", T0.plus(TTL)));
        assertEquals(0, cache.totalCandles());
    }

    @Test
    void replacingAKeyDoesNotDoubleCount() {
        BinanceKlineClient.CandleCache cache = new BinanceKlineClient.CandleCache(TTL, 10, 1_000);
        cache.put("a", candles(10), T0);
        cache.put("a", candles(30), T0.plusSeconds(1));

        assertEquals(1, cache.size());
        assertEquals(30, cache.totalCandles());
    }
}
