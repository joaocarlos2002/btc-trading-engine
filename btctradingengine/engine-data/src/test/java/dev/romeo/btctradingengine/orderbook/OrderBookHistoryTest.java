package dev.romeo.btctradingengine.orderbook;

import dev.romeo.btctradingengine.model.CandleEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class OrderBookHistoryTest {

    @Test
    public void candleGetsTheMeanOfTheSnapshotsTakenWhileItWasOpen() {
        OrderBookHistory history = new OrderBookHistory(Duration.ZERO);
        history.add(Instant.parse("2026-09-08T09:59:55Z"), new BigDecimal("0.9"));   // previous candle
        history.add(Instant.parse("2026-09-08T10:00:05Z"), new BigDecimal("0.4"));
        history.add(Instant.parse("2026-09-08T10:00:35Z"), new BigDecimal("0.6"));
        history.add(Instant.parse("2026-09-08T10:00:59.999Z"), new BigDecimal("0.5")); // last millisecond: still this candle
        history.add(Instant.parse("2026-09-08T10:01:00Z"), new BigDecimal("0.1"));   // next minute: next candle

        assertEquals(0, history.imbalanceFor(candle("2026-09-08T10:00:00Z")).compareTo(new BigDecimal("0.5")));
    }

    @Test
    public void candleWithoutSnapshotsHasNoImbalance() {
        OrderBookHistory history = new OrderBookHistory(Duration.ZERO);
        history.add(Instant.parse("2026-09-08T10:00:05Z"), new BigDecimal("0.4"));

        assertNull(history.imbalanceFor(candle("2026-09-08T10:05:00Z")));
    }

    @Test
    public void retentionDropsOldSnapshots() {
        OrderBookHistory history = new OrderBookHistory(Duration.ofMinutes(30));
        history.add(Instant.parse("2026-09-08T10:00:05Z"), new BigDecimal("0.4"));
        history.add(Instant.parse("2026-09-08T11:00:05Z"), new BigDecimal("0.6"));

        assertNull(history.imbalanceFor(candle("2026-09-08T10:00:00Z")));
    }

    /** A 1m candle: closeTime is the last millisecond of the minute, live or from klines (issue #75). */
    private static CandleEvent candle(String openTime) {
        Instant open = Instant.parse(openTime);
        return new CandleEvent("BTCUSDT", open, open.plusSeconds(60).minusMillis(1),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1);
    }
}
