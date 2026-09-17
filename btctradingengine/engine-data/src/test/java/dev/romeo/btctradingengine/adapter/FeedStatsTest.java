package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #104: tick count, lag and last tick age of the market data feed. */
class FeedStatsTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final FeedStats stats = new FeedStats(now::get);

    private static NormalizedPriceEvent tick(long tradeMs, long receiptMs) {
        return new NormalizedPriceEvent("BTCUSDT", new BigDecimal("100"),
                Instant.ofEpochMilli(tradeMs), Instant.ofEpochMilli(receiptMs));
    }

    @Test
    void beforeTheFirstTickTheAgeCountsFromCreation() {
        now.addAndGet(5_000);

        assertFalse(stats.hasReceivedTicks());
        assertEquals(0, stats.ticksTotal());
        assertEquals(5_000, stats.millisSinceLastTick());
    }

    @Test
    void recordsCountLagAndAge() {
        stats.record(tick(1_000_000, 1_000_120));
        stats.record(tick(1_000_500, 1_000_530));
        now.set(1_002_530);

        assertTrue(stats.hasReceivedTicks());
        assertEquals(2, stats.ticksTotal());
        assertEquals(30, stats.lastLagMillis(), "lag of the latest tick");
        assertEquals(2_000, stats.millisSinceLastTick());
    }
}
