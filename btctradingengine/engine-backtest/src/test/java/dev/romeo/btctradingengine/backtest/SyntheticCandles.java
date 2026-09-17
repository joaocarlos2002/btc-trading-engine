package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Deterministic candles and fast strategy params for backtest tests - no network. */
final class SyntheticCandles {
    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private SyntheticCandles() { }

    /** Two overlaid waves, so there are swings for the rules to trade on. */
    static List<CandleEvent> waves(Instant start, Duration interval, int count) {
        List<CandleEvent> candles = new ArrayList<>(count);
        double previous = price(-1);
        for (int i = 0; i < count; i++) {
            double close = price(i);
            double open = previous;
            double high = Math.max(open, close) * 1.002;
            double low = Math.min(open, close) * 0.998;
            Instant openTime = start.plus(interval.multipliedBy(i));
            candles.add(new CandleEvent("BTCUSDT", openTime, openTime.plus(interval).minusMillis(1),
                    bd(open), bd(high), bd(low), bd(close), bd(10 + (i % 7)), 50));
            previous = close;
        }
        return candles;
    }

    static List<CandleEvent> hourly(int days) {
        return waves(START, Duration.ofHours(1), days * 24);
    }

    private static double price(int i) {
        return 100 + 8 * Math.sin(2 * Math.PI * i / 40.0) + 3 * Math.sin(2 * Math.PI * i / 13.0);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }

    /** Short periods and loose thresholds, so a few hundred candles are enough to trade. */
    static BacktestParams fastParams() {
        return BacktestParams.defaults().withOverrides(Map.ofEntries(
                Map.entry("smaPeriod", 10), Map.entry("emaPeriod", 10), Map.entry("rsiPeriod", 7),
                Map.entry("atrPeriod", 7), Map.entry("macdFastPeriod", 6), Map.entry("macdSlowPeriod", 13),
                Map.entry("macdSignalPeriod", 5), Map.entry("volatilityShortPeriods", 5),
                Map.entry("volatilityLongPeriods", 20), Map.entry("volumeAveragePeriods", 20),
                Map.entry("adxPeriod", 7), Map.entry("bollingerPeriod", 10), Map.entry("mfiPeriod", 7),
                Map.entry("donchianPeriod", 10), Map.entry("vwapRollingPeriods", 20),
                Map.entry("priceActionLookback", 10), Map.entry("priceActionSwingStrength", 2),
                Map.entry("cvdPeriod", 10), Map.entry("emaSlopePeriods", 3), Map.entry("vpinBuckets", 5),
                Map.entry("vpinBucketCandles", 5), Map.entry("absorptionWindow", 10),
                Map.entry("buyThreshold", 0.05), Map.entry("sellThreshold", -0.05),
                Map.entry("confirmationSnapshots", 1), Map.entry("regimeGatingEnabled", false),
                Map.entry("vpinFilterEnabled", false), Map.entry("allowShort", false),
                Map.entry("targetPercent", 3), Map.entry("stopLossPercent", 3),
                Map.entry("commissionRate", 0)));
    }
}
