package dev.romeo.btctradingengine.indicator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Bollinger Bands over closes, exposing Width and %B.
 *
 * Width is a regime feature (squeeze detector, complements ATR%) and belongs in a filter rule.
 * %B is context only - it confirms/modulates conviction and must not become a directional rule
 * of its own.
 *
 * Standard deviation is the population form over the window (divides by the period, not by
 * period-1), matching the classic Bollinger definition.
 */
public class BollingerBands {
    private static final MathContext SQRT_CONTEXT = new MathContext(16, RoundingMode.HALF_EVEN);

    private final int period;
    private final BigDecimal stdDevMultiplier;
    private final Deque<BigDecimal> closes = new ArrayDeque<>();
    private BigDecimal runningSum = BigDecimal.ZERO;
    /** Sum of close^2 over the window, so the variance needs no rescan (issue #95). */
    private BigDecimal runningSumOfSquares = BigDecimal.ZERO;

    public BollingerBands(int period, BigDecimal stdDevMultiplier) {
        this.period = period;
        this.stdDevMultiplier = stdDevMultiplier;
    }

    public Optional<BollingerValue> update(BigDecimal close) {
        runningSum = runningSum.add(close);
        runningSumOfSquares = runningSumOfSquares.add(close.multiply(close));
        closes.addLast(close);

        if (closes.size() > period) {
            BigDecimal oldest = closes.removeFirst();
            runningSum = runningSum.subtract(oldest);
            runningSumOfSquares = runningSumOfSquares.subtract(oldest.multiply(oldest));
        }
        if (closes.size() < period) {
            return Optional.empty();
        }

        BigDecimal middle = runningSum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_EVEN);
        BigDecimal standardDeviation = standardDeviation(middle);
        BigDecimal offset = stdDevMultiplier.multiply(standardDeviation);

        BigDecimal upper = middle.add(offset);
        BigDecimal lower = middle.subtract(offset);

        return Optional.of(new BollingerValue(
                upper,
                middle,
                lower,
                calculateWidth(upper, lower, middle),
                calculatePercentB(close, upper, lower)
        ));
    }

    /**
     * Sum of (close - middle)^2 expanded as sum(c^2) - 2*middle*sum(c) + period*middle^2. BigDecimal
     * add/subtract/multiply are exact, so this is the very same number the per-close loop produced,
     * middle's 8-decimal rounding included - there is no floating drift to resync.
     */
    private BigDecimal standardDeviation(BigDecimal middle) {
        BigDecimal sumOfSquares = runningSumOfSquares
                .subtract(middle.multiply(runningSum).multiply(BigDecimal.TWO))
                .add(middle.multiply(middle).multiply(BigDecimal.valueOf(period)));
        BigDecimal variance = sumOfSquares.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_EVEN);

        if (variance.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return variance.sqrt(SQRT_CONTEXT).setScale(8, RoundingMode.HALF_EVEN);
    }

    /** Band width as a percentage of the middle band; a zero middle band would blow up the division. */
    private BigDecimal calculateWidth(BigDecimal upper, BigDecimal lower, BigDecimal middle) {
        if (middle.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return upper.subtract(lower)
                .divide(middle, 8, RoundingMode.HALF_EVEN)
                .multiply(BigDecimal.valueOf(100));
    }

    /** A fully flat window collapses the bands; %B is then undefined, so report the midpoint. */
    private BigDecimal calculatePercentB(BigDecimal close, BigDecimal upper, BigDecimal lower) {
        BigDecimal bandRange = upper.subtract(lower);
        if (bandRange.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(0.5).setScale(8, RoundingMode.HALF_EVEN);
        }
        return close.subtract(lower)
                .divide(bandRange, 8, RoundingMode.HALF_EVEN);
    }

    public record BollingerValue(
            BigDecimal upper,
            BigDecimal middle,
            BigDecimal lower,
            BigDecimal width,
            BigDecimal percentB
    ) {}
}
