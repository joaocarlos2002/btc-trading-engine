package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CandleAggregatorTest {

    @Test
    public void opensCandleWithFirstEvent() {
        List<CandleEvent> candles = new ArrayList<>();
        CandleAggregator aggregator = new CandleAggregator(Duration.ofMinutes(15), candles::add);

        aggregator.onEvent(event("100", "2026-09-08T10:07:30Z"));

        assertEquals(0, candles.size());
    }

    @Test
    public void updatesCandleInsideSameIntervalAndEmitsOnRollover() {
        List<CandleEvent> candles = new ArrayList<>();
        CandleAggregator aggregator = new CandleAggregator(Duration.ofMinutes(15), candles::add);

        aggregator.onEvent(event("100", "2026-09-08T10:07:30Z", "2"));
        aggregator.onEvent(event("110", "2026-09-08T10:08:00Z", "3"));
        aggregator.onEvent(event("90", "2026-09-08T10:14:59Z", "5"));
        aggregator.onEvent(event("95", "2026-09-08T10:15:00Z", "7"));

        assertEquals(1, candles.size());
        CandleEvent candle = candles.get(0);
        assertEquals("BTC/USD", candle.instrument());
        assertEquals(Instant.parse("2026-09-08T10:00:00Z"), candle.openTime());
        assertEquals(Instant.parse("2026-09-08T10:15:00Z"), candle.closeTime());
        assertEquals(new BigDecimal("100"), candle.open());
        assertEquals(new BigDecimal("110"), candle.high());
        assertEquals(new BigDecimal("90"), candle.low());
        assertEquals(new BigDecimal("90"), candle.close());
        assertEquals(new BigDecimal("10"), candle.volume());
        assertEquals(3, candle.tickCount());
    }

    @Test
    public void startsNewCandleAfterRollover() {
        List<CandleEvent> candles = new ArrayList<>();
        CandleAggregator aggregator = new CandleAggregator(Duration.ofMinutes(15), candles::add);

        aggregator.onEvent(event("100", "2026-09-08T10:14:59Z", "2"));
        aggregator.onEvent(event("95", "2026-09-08T10:15:00Z", "3"));
        aggregator.onEvent(event("105", "2026-09-08T10:16:00Z", "4"));
        aggregator.onEvent(event("80", "2026-09-08T10:30:00Z", "5"));

        assertEquals(2, candles.size());
        CandleEvent candle = candles.get(1);
        assertEquals("BTC/USD", candle.instrument());
        assertEquals(Instant.parse("2026-09-08T10:15:00Z"), candle.openTime());
        assertEquals(Instant.parse("2026-09-08T10:30:00Z"), candle.closeTime());
        assertEquals(new BigDecimal("95"), candle.open());
        assertEquals(new BigDecimal("105"), candle.high());
        assertEquals(new BigDecimal("95"), candle.low());
        assertEquals(new BigDecimal("105"), candle.close());
        assertEquals(new BigDecimal("7"), candle.volume());
        assertEquals(2, candle.tickCount());
    }

    @Test
    public void rejectsNonPositiveInterval() {
        List<CandleEvent> candles = new ArrayList<>();

        assertThrows(IllegalArgumentException.class,
                () -> new CandleAggregator(Duration.ZERO, candles::add));
        assertThrows(IllegalArgumentException.class,
                () -> new CandleAggregator(Duration.ofMinutes(-1), candles::add));
    }

    private NormalizedPriceEvent event(String price, String timestamp) {
        return event(price, timestamp, "0");
    }

    private NormalizedPriceEvent event(String price, String timestamp, String quantity) {
        Instant eventTimestamp = Instant.parse(timestamp);
        return new NormalizedPriceEvent(
                "BTC/USD",
                new BigDecimal(price),
                eventTimestamp,
                eventTimestamp,
                new BigDecimal(quantity));
    }
}


