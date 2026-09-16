package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.model.AggressorSide;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.model.TradeFlow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CandleAggregatorTest {
    /** feature.flow.large.trade.notional as shipped. */
    private static final BigDecimal LARGE = new BigDecimal("100000");

    @Test
    public void opensCandleWithFirstEvent() {
        List<CandleEvent> candles = new ArrayList<>();
        CandleAggregator aggregator = new CandleAggregator(Duration.ofMinutes(15), candles::add, LARGE);

        aggregator.onEvent(event("100", "2026-09-08T10:07:30Z"));

        assertEquals(0, candles.size());
    }

    @Test
    public void updatesCandleInsideSameIntervalAndEmitsOnRollover() {
        List<CandleEvent> candles = new ArrayList<>();
        CandleAggregator aggregator = new CandleAggregator(Duration.ofMinutes(15), candles::add, LARGE);

        aggregator.onEvent(event("100", "2026-09-08T10:07:30Z", "2"));
        aggregator.onEvent(event("110", "2026-09-08T10:08:00Z", "3"));
        aggregator.onEvent(event("90", "2026-09-08T10:14:59Z", "5"));
        aggregator.onEvent(event("95", "2026-09-08T10:15:00Z", "7"));

        assertEquals(1, candles.size());
        CandleEvent candle = candles.get(0);
        assertEquals("BTC/USD", candle.instrument());
        assertEquals(Instant.parse("2026-09-08T10:00:00Z"), candle.openTime());
        assertEquals(Instant.parse("2026-09-08T10:14:59.999Z"), candle.closeTime());
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
        CandleAggregator aggregator = new CandleAggregator(Duration.ofMinutes(15), candles::add, LARGE);

        aggregator.onEvent(event("100", "2026-09-08T10:14:59Z", "2"));
        aggregator.onEvent(event("95", "2026-09-08T10:15:00Z", "3"));
        aggregator.onEvent(event("105", "2026-09-08T10:16:00Z", "4"));
        aggregator.onEvent(event("80", "2026-09-08T10:30:00Z", "5"));

        assertEquals(2, candles.size());
        CandleEvent candle = candles.get(1);
        assertEquals("BTC/USD", candle.instrument());
        assertEquals(Instant.parse("2026-09-08T10:15:00Z"), candle.openTime());
        assertEquals(Instant.parse("2026-09-08T10:29:59.999Z"), candle.closeTime());
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
                () -> new CandleAggregator(Duration.ZERO, candles::add, LARGE));
        assertThrows(IllegalArgumentException.class,
                () -> new CandleAggregator(Duration.ofMinutes(-1), candles::add, LARGE));
    }

    @Test
    public void splitsVolumeByAggressorSideAndTradeSize() {
        List<CandleEvent> candles = new ArrayList<>();
        CandleAggregator aggregator = new CandleAggregator(Duration.ofMinutes(15), candles::add, new BigDecimal("1000"));

        aggregator.onEvent(event("100", "2026-09-08T10:01:00Z", "2", AggressorSide.BUY));   // 200 notional
        aggregator.onEvent(event("100", "2026-09-08T10:02:00Z", "15", AggressorSide.BUY));  // 1500, large
        aggregator.onEvent(event("100", "2026-09-08T10:03:00Z", "4", AggressorSide.SELL));  // 400
        aggregator.onEvent(event("100", "2026-09-08T10:04:00Z", "10", AggressorSide.SELL)); // 1000, large (inclusive)
        aggregator.onEvent(event("100", "2026-09-08T10:15:00Z", "1", AggressorSide.BUY));   // rollover

        TradeFlow flow = candles.get(0).flow();
        assertEquals(TradeFlow.Source.TRADES, flow.source());
        assertEquals(0, flow.takerBuyVolume().compareTo(new BigDecimal("17")));
        assertEquals(0, flow.takerSellVolume().compareTo(new BigDecimal("14")));
        assertEquals(0, flow.largeBuyVolume().compareTo(new BigDecimal("15")));
        assertEquals(0, flow.largeSellVolume().compareTo(new BigDecimal("10")));
    }

    @Test
    public void oneTradeWithUnknownSideDropsTheCandleFlow() {
        List<CandleEvent> candles = new ArrayList<>();
        CandleAggregator aggregator = new CandleAggregator(Duration.ofMinutes(15), candles::add, new BigDecimal("1000"));

        aggregator.onEvent(event("100", "2026-09-08T10:01:00Z", "2", AggressorSide.BUY));
        aggregator.onEvent(event("100", "2026-09-08T10:02:00Z", "3", AggressorSide.UNKNOWN));
        aggregator.onEvent(event("100", "2026-09-08T10:15:00Z", "1", AggressorSide.BUY));

        assertEquals(TradeFlow.Source.NONE, candles.get(0).flow().source());
        // the next candle starts clean
        aggregator.onEvent(event("100", "2026-09-08T10:30:00Z", "1", AggressorSide.BUY));
        assertEquals(TradeFlow.Source.TRADES, candles.get(1).flow().source());
    }

    @Test
    public void lateTickAfterTimerCloseDoesNotEmitTheCandleAgain() {
        List<CandleEvent> candles = new ArrayList<>();
        CandleAggregator aggregator = new CandleAggregator(Duration.ofMinutes(1), candles::add, LARGE);

        aggregator.onEvent(event("100", "2026-09-08T10:00:30Z", "1"));
        aggregator.closeExpiredCandle(Instant.parse("2026-09-08T10:01:00.250Z"));
        assertEquals(1, candles.size());

        // a trade from the window that was just emitted arrives after the timer closed it (issue #74)
        aggregator.onEvent(event("101", "2026-09-08T10:00:59.900Z", "1"));
        aggregator.closeExpiredCandle(Instant.parse("2026-09-08T10:02:00.250Z"));
        aggregator.onEvent(event("102", "2026-09-08T10:02:10Z", "1"));
        aggregator.onEvent(event("103", "2026-09-08T10:03:10Z", "1"));

        assertEquals(2, candles.size());
        assertEquals(Instant.parse("2026-09-08T10:00:00Z"), candles.get(0).openTime());
        assertEquals(Instant.parse("2026-09-08T10:02:00Z"), candles.get(1).openTime());
        assertEquals(new BigDecimal("102"), candles.get(1).open());
        assertEquals(1, aggregator.getLateTicksDropped());
    }

    @Test
    public void liveCandleHasTheSameCloseTimeAsTheKlineOfThatMinute() {
        // Binance kline row for the 1m candle opening at 2026-09-08T10:00:00Z: [openTime, ..., closeTime, ...]
        long klineOpenTime = 1788861600000L;
        long klineCloseTime = 1788861659999L;
        List<CandleEvent> candles = new ArrayList<>();
        CandleAggregator aggregator = new CandleAggregator(Duration.ofMinutes(1), candles::add, LARGE);

        aggregator.onEvent(event("100", "2026-09-08T10:00:30Z", "1"));
        aggregator.closeExpiredCandle(Instant.parse("2026-09-08T10:01:00.250Z"));
        aggregator.onEvent(event("100", "2026-09-08T10:01:30Z", "1"));
        aggregator.onEvent(event("100", "2026-09-08T10:02:00Z", "1"));   // rollover path

        assertEquals(Instant.ofEpochMilli(klineOpenTime), candles.get(0).openTime());
        assertEquals(Instant.ofEpochMilli(klineCloseTime), candles.get(0).closeTime());
        assertEquals(Instant.ofEpochMilli(klineCloseTime + 60_000), candles.get(1).closeTime());
    }

    @Test
    public void tickOlderThanTheOpenCandleIsDropped() {
        List<CandleEvent> candles = new ArrayList<>();
        CandleAggregator aggregator = new CandleAggregator(Duration.ofMinutes(1), candles::add, LARGE);

        aggregator.onEvent(event("100", "2026-09-08T10:05:10Z", "1"));
        aggregator.onEvent(event("90", "2026-09-08T10:04:59Z", "1"));
        aggregator.onEvent(event("110", "2026-09-08T10:06:00Z", "1"));

        assertEquals(1, candles.size());
        assertEquals(Instant.parse("2026-09-08T10:05:00Z"), candles.get(0).openTime());
        assertEquals(new BigDecimal("100"), candles.get(0).low());
        assertEquals(1, aggregator.getLateTicksDropped());
    }

    @Test
    public void timerIsAlignedToTheNextIntervalBoundaryPlusTolerance() {
        CandleAggregator aggregator = new CandleAggregator(Duration.ofMinutes(1), candles -> { }, LARGE);

        assertEquals(20_250, aggregator.initialTimerDelayMillis(Instant.parse("2026-09-08T10:00:40Z")));
        assertEquals(60_250, aggregator.initialTimerDelayMillis(Instant.parse("2026-09-08T10:00:00Z")));
    }

    @Test
    public void binanceBuyerMakerFlagMeansTheSellerWasTheAggressor() {
        assertEquals(AggressorSide.SELL, AggressorSide.fromBuyerMaker(true));
        assertEquals(AggressorSide.BUY, AggressorSide.fromBuyerMaker(false));
    }

    private NormalizedPriceEvent event(String price, String timestamp, String quantity, AggressorSide side) {
        Instant eventTimestamp = Instant.parse(timestamp);
        return new NormalizedPriceEvent(
                "BTC/USD",
                new BigDecimal(price),
                eventTimestamp,
                eventTimestamp,
                new BigDecimal(quantity),
                side);
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


