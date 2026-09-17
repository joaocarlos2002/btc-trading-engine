package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Price action read straight from the candles (issue #6): candle anatomy, bullish/bearish streak,
 * breakout of the previous candle, distance to the recent high/low, swing structure (higher/lower
 * highs and lows) and the nearest support/resistance.
 *
 * Swing points are pivots: a candle whose high (low) beats the swingStrength candles on each side.
 * A pivot can only be known swingStrength candles after it printed, so every swing-derived field
 * lags by that much - on purpose, since reading the right side early would be lookahead.
 *
 * Unlike the other indicators this one always returns a value: the anatomy fields need no history,
 * and each history-dependent field falls back to 0 on its own while it is not available yet.
 */
public class PriceAction {
    private static final int SCALE = 8;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final int lookback;
    private final int swingStrength;

    /** High/low of the lookback candles BEFORE the current one, so a breakout can be measured. */
    private final RollingExtremum priorHighs;
    private final RollingExtremum priorLows;
    /** The last 2*swingStrength+1 candles; the one in the middle is the pivot candidate. */
    private final Deque<CandleEvent> pivotWindow = new ArrayDeque<>();
    /** Confirmed pivots still inside the lookback window, oldest first. */
    private final Deque<SwingPoint> swings = new ArrayDeque<>();

    private CandleEvent previous;
    private int streak;
    private long index = -1;

    public PriceAction(int lookback, int swingStrength) {
        this.lookback = lookback;
        this.swingStrength = swingStrength;
        this.priorHighs = RollingExtremum.max(lookback);
        this.priorLows = RollingExtremum.min(lookback);
    }

    public PriceActionValue update(CandleEvent candle) {
        index++;
        BigDecimal close = candle.close();

        int previousBreak = previousCandleBreak(candle);
        streak = nextStreak(candle);

        BigDecimal recentHighDistance = BigDecimal.ZERO;
        BigDecimal recentLowDistance = BigDecimal.ZERO;
        if (priorHighs.size() == lookback) {
            recentHighDistance = percentFromClose(close, priorHighs.value());
            recentLowDistance = percentFromClose(close, priorLows.value());
        }

        detectPivot(candle);
        swings.removeIf(swing -> swing.index() < index - lookback);

        BigDecimal[] body = anatomy(candle);
        PriceActionValue value = new PriceActionValue(
                body[0], body[1], body[2],
                streak,
                previousBreak,
                recentHighDistance,
                recentLowDistance,
                swingTrend(true),
                swingTrend(false),
                supportDistance(close),
                resistanceDistance(close));

        remember(candle);
        return value;
    }

    /** Body, upper wick and lower wick as fractions of the candle range; they add up to 1. */
    private BigDecimal[] anatomy(CandleEvent candle) {
        BigDecimal range = candle.high().subtract(candle.low());
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        }
        BigDecimal bodyTop = candle.open().max(candle.close());
        BigDecimal bodyBottom = candle.open().min(candle.close());
        return new BigDecimal[] {
                bodyTop.subtract(bodyBottom).divide(range, SCALE, RoundingMode.HALF_EVEN),
                candle.high().subtract(bodyTop).divide(range, SCALE, RoundingMode.HALF_EVEN),
                bodyBottom.subtract(candle.low()).divide(range, SCALE, RoundingMode.HALF_EVEN)
        };
    }

    /** +n after n bullish candles in a row, -n after n bearish ones; a doji resets it to 0. */
    private int nextStreak(CandleEvent candle) {
        int direction = candle.close().compareTo(candle.open());
        if (direction > 0) {
            return streak > 0 ? streak + 1 : 1;
        }
        if (direction < 0) {
            return streak < 0 ? streak - 1 : -1;
        }
        return 0;
    }

    /** +1 when the close is above the previous candle's high, -1 below its low. Wicks alone don't count. */
    private int previousCandleBreak(CandleEvent candle) {
        if (previous == null) {
            return 0;
        }
        if (candle.close().compareTo(previous.high()) > 0) {
            return 1;
        }
        if (candle.close().compareTo(previous.low()) < 0) {
            return -1;
        }
        return 0;
    }

    /**
     * Ties go to the earlier candle: the candidate must be strictly above the left side and at least
     * equal to the right side, so a flat double top yields one pivot instead of none.
     */
    private void detectPivot(CandleEvent candle) {
        pivotWindow.addLast(candle);
        if (pivotWindow.size() > 2 * swingStrength + 1) {
            pivotWindow.removeFirst();
        }
        if (pivotWindow.size() < 2 * swingStrength + 1) {
            return;
        }

        List<CandleEvent> window = new ArrayList<>(pivotWindow);
        CandleEvent candidate = window.get(swingStrength);
        boolean isHigh = true;
        boolean isLow = true;
        for (int i = 0; i < window.size(); i++) {
            if (i == swingStrength) {
                continue;
            }
            int highCmp = candidate.high().compareTo(window.get(i).high());
            int lowCmp = candidate.low().compareTo(window.get(i).low());
            if (i < swingStrength) {
                isHigh &= highCmp > 0;
                isLow &= lowCmp < 0;
            } else {
                isHigh &= highCmp >= 0;
                isLow &= lowCmp <= 0;
            }
        }

        long candidateIndex = index - swingStrength;
        if (isHigh) {
            swings.addLast(new SwingPoint(candidateIndex, candidate.high(), true));
        }
        if (isLow) {
            swings.addLast(new SwingPoint(candidateIndex, candidate.low(), false));
        }
    }

    /** +1 if the last swing high (low) is above the one before it, -1 if below, 0 if unknown or equal. */
    private int swingTrend(boolean highs) {
        BigDecimal last = null;
        BigDecimal beforeLast = null;
        for (SwingPoint swing : swings) {
            if (swing.high() == highs) {
                beforeLast = last;
                last = swing.price();
            }
        }
        if (beforeLast == null) {
            return 0;
        }
        return last.compareTo(beforeLast);
    }

    /**
     * Nearest swing level below the close, as a % of the close. Both swing highs and lows count as
     * levels, since a broken resistance tends to act as support afterwards. 0 when there is none.
     */
    private BigDecimal supportDistance(BigDecimal close) {
        BigDecimal support = null;
        for (SwingPoint swing : swings) {
            if (swing.price().compareTo(close) < 0) {
                support = support == null ? swing.price() : support.max(swing.price());
            }
        }
        return support == null ? BigDecimal.ZERO : percentFromClose(close, support);
    }

    /** Nearest swing level above the close, as a (positive) % of the close. 0 when there is none. */
    private BigDecimal resistanceDistance(BigDecimal close) {
        BigDecimal resistance = null;
        for (SwingPoint swing : swings) {
            if (swing.price().compareTo(close) > 0) {
                resistance = resistance == null ? swing.price() : resistance.min(swing.price());
            }
        }
        return resistance == null ? BigDecimal.ZERO : percentFromClose(close, resistance).negate();
    }

    private void remember(CandleEvent candle) {
        previous = candle;
        priorHighs.add(candle.high());
        priorLows.add(candle.low());
    }

    /** (close - level) / close * 100, the same convention as smaDistance. */
    private BigDecimal percentFromClose(BigDecimal close, BigDecimal level) {
        if (close.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return close.subtract(level)
                .divide(close, SCALE, RoundingMode.HALF_EVEN)
                .multiply(HUNDRED);
    }

    private record SwingPoint(long index, BigDecimal price, boolean high) {}

    public record PriceActionValue(
            BigDecimal bodyRatio,
            BigDecimal upperWickRatio,
            BigDecimal lowerWickRatio,
            int candleStreak,
            int previousCandleBreak,
            BigDecimal recentHighDistance,
            BigDecimal recentLowDistance,
            int swingHighTrend,
            int swingLowTrend,
            BigDecimal supportDistance,
            BigDecimal resistanceDistance
    ) {}
}
