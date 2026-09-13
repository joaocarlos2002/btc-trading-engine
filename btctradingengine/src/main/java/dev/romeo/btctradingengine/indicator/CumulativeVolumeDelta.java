package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.TradeFlow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Cumulative Volume Delta over a rolling window (issue #11): aggressive buy volume minus aggressive
 * sell volume, per candle and summed over the last period candles, plus the same sum restricted to
 * large trades.
 *
 * Rolling rather than cumulative since startup: a running total from an arbitrary start point makes
 * the absolute value depend on when the bot, or the backtest range, began. Even so the raw cvd is in
 * base units and scales with market activity, so cvdRatio - the cvd over the taker volume of the
 * window, in [-1, 1] - is the number that stays comparable across regimes.
 *
 * Candles without flow data contribute neither delta nor volume, so a gap does not dilute the ratio
 * as if flow had been balanced. The size split only accumulates candles built from live trades
 * (klines carry no trade sizes), which is why largeCvd and largeVolumeShare stay 0 in backtests.
 *
 * Always returns a value: every field is a sum over whatever part of the window has been seen.
 */
public class CumulativeVolumeDelta {
    private static final int SCALE = 8;

    private final int period;
    private final Deque<Entry> window = new ArrayDeque<>();

    private BigDecimal cvd = BigDecimal.ZERO;
    private BigDecimal takerVolume = BigDecimal.ZERO;
    private BigDecimal largeCvd = BigDecimal.ZERO;
    private BigDecimal largeVolume = BigDecimal.ZERO;
    private BigDecimal sizeSplitVolume = BigDecimal.ZERO;

    public CumulativeVolumeDelta(int period) {
        this.period = period;
    }

    public CvdValue update(CandleEvent candle) {
        Entry entry = Entry.of(candle.flow());

        window.addLast(entry);
        accumulate(entry, 1);
        if (window.size() > period) {
            accumulate(window.removeFirst(), -1);
        }

        return new CvdValue(
                entry.delta(),
                ratio(entry.delta(), entry.takerVolume()),
                cvd,
                ratio(cvd, takerVolume),
                largeCvd,
                ratio(largeVolume, sizeSplitVolume));
    }

    /** Running sums instead of re-summing the window: BigDecimal add/subtract is exact, so no drift. */
    private void accumulate(Entry entry, int sign) {
        BigDecimal factor = BigDecimal.valueOf(sign);
        cvd = cvd.add(entry.delta().multiply(factor));
        takerVolume = takerVolume.add(entry.takerVolume().multiply(factor));
        largeCvd = largeCvd.add(entry.largeDelta().multiply(factor));
        largeVolume = largeVolume.add(entry.largeVolume().multiply(factor));
        sizeSplitVolume = sizeSplitVolume.add(entry.sizeSplitVolume().multiply(factor));
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, SCALE, RoundingMode.HALF_EVEN);
    }

    private record Entry(
            BigDecimal delta,
            BigDecimal takerVolume,
            BigDecimal largeDelta,
            BigDecimal largeVolume,
            BigDecimal sizeSplitVolume
    ) {
        /** TradeFlow.none() is all zeros, so a candle without flow data adds nothing. */
        static Entry of(TradeFlow flow) {
            BigDecimal takerVolume = flow.takerBuyVolume().add(flow.takerSellVolume());
            return new Entry(
                    flow.delta(),
                    takerVolume,
                    flow.largeBuyVolume().subtract(flow.largeSellVolume()),
                    flow.largeBuyVolume().add(flow.largeSellVolume()),
                    flow.hasSizeSplit() ? takerVolume : BigDecimal.ZERO);
        }
    }

    public record CvdValue(
            BigDecimal volumeDelta,
            BigDecimal deltaRatio,
            BigDecimal cvd,
            BigDecimal cvdRatio,
            BigDecimal largeCvd,
            BigDecimal largeVolumeShare
    ) {}
}
