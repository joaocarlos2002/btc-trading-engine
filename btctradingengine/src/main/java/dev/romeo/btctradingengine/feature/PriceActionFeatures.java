package dev.romeo.btctradingengine.feature;

import dev.romeo.btctradingengine.indicator.PriceAction;

import java.math.BigDecimal;

/**
 * Price action layer (issue #6): what the candles themselves say, with no smoothing. Like context,
 * these fields describe the market and have no directional rule of their own yet - wiring any of
 * them into the score average would change its denominator and require re-running the backtest
 * sweep (see the prediction.buy.threshold note in application.properties).
 *
 * 0 means "not available yet" for the history-dependent fields. Distances follow the smaDistance
 * convention, (close - level) / close * 100, except resistanceDistance which is flipped so both
 * support and resistance distances read as positive room.
 */
public record PriceActionFeatures(
        BigDecimal bodyRatio,             // |close - open| / (high - low) [0-1]
        BigDecimal upperWickRatio,        // (high - max(open, close)) / (high - low) [0-1]
        BigDecimal lowerWickRatio,        // (min(open, close) - low) / (high - low) [0-1]
        int candleStreak,                 // +n bullish candles in a row, -n bearish, 0 on doji
        int previousCandleBreak,          // +1 close above previous high, -1 below previous low
        BigDecimal recentHighDistance,    // vs highest high of the previous N candles; > 0 = breakout
        BigDecimal recentLowDistance,     // vs lowest low of the previous N candles; < 0 = breakdown
        int swingHighTrend,               // +1 higher high, -1 lower high, 0 unknown
        int swingLowTrend,                // +1 higher low, -1 lower low, 0 unknown
        BigDecimal supportDistance,       // % down to the nearest swing level below the close
        BigDecimal resistanceDistance     // % up to the nearest swing level above the close
) {

    public static PriceActionFeatures empty() {
        return new PriceActionFeatures(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public static PriceActionFeatures from(PriceAction.PriceActionValue value) {
        return new PriceActionFeatures(
                value.bodyRatio(), value.upperWickRatio(), value.lowerWickRatio(),
                value.candleStreak(), value.previousCandleBreak(),
                value.recentHighDistance(), value.recentLowDistance(),
                value.swingHighTrend(), value.swingLowTrend(),
                value.supportDistance(), value.resistanceDistance());
    }

    /** Higher highs and higher lows = +1 (uptrend), lower highs and lower lows = -1, otherwise 0. */
    public int marketStructure() {
        return swingHighTrend == swingLowTrend ? swingHighTrend : 0;
    }
}
