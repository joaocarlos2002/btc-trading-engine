package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Absorption (issue #12): heavy aggression on one side that does not move price, because passive
 * orders on the other side soak it up.
 *
 * <pre>
 * strong sell aggression + price does not fall -> buy absorption  (positive, bullish)
 * strong buy aggression  + price does not rise -> sell absorption (negative, bearish)
 * </pre>
 *
 * A candle qualifies when |deltaRatio| is at least deltaMin, volume is at least volumeRatioMin times
 * the average (effort has to be real, a lopsided 3-trade candle is not absorption), and price moved
 * no more than maxMoveAtr ATRs in the direction of the aggression. Its strength is the delta ratio
 * with the sign flipped, so +0.6 reads "60% net selling, absorbed".
 *
 * This is an inference from trades alone: without the order book (issue #10) the passive orders that
 * did the absorbing cannot be seen, only the lack of a price response. Candles without flow data and
 * candles before ATR warms up report 0. None of the thresholds is calibrated.
 */
public class Absorption {
    private final BigDecimal deltaMin;
    private final BigDecimal volumeRatioMin;
    private final BigDecimal maxMoveAtr;
    private final int window;

    private final Deque<BigDecimal> recent = new ArrayDeque<>();
    private BigDecimal windowSum = BigDecimal.ZERO;

    public Absorption(BigDecimal deltaMin, BigDecimal volumeRatioMin, BigDecimal maxMoveAtr, int window) {
        this.deltaMin = deltaMin;
        this.volumeRatioMin = volumeRatioMin;
        this.maxMoveAtr = maxMoveAtr;
        this.window = window;
    }

    public AbsorptionValue update(CandleEvent candle, BigDecimal deltaRatio, BigDecimal volumeRatio, BigDecimal atr) {
        BigDecimal strength = strength(candle, deltaRatio, volumeRatio, atr);

        recent.addLast(strength);
        windowSum = windowSum.add(strength);
        if (recent.size() > window) {
            windowSum = windowSum.subtract(recent.removeFirst());
        }
        return new AbsorptionValue(strength, windowSum);
    }

    private BigDecimal strength(CandleEvent candle, BigDecimal deltaRatio, BigDecimal volumeRatio, BigDecimal atr) {
        if (!candle.flow().hasTakerSplit()
                || atr.signum() <= 0
                || volumeRatio.compareTo(volumeRatioMin) < 0
                || deltaRatio.abs().compareTo(deltaMin) < 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal allowedMove = atr.multiply(maxMoveAtr);
        BigDecimal move = candle.close().subtract(candle.open());

        // Selling pressure that did not push price down more than the allowed move
        if (deltaRatio.signum() < 0 && move.compareTo(allowedMove.negate()) >= 0) {
            return deltaRatio.negate();
        }
        // Buying pressure that did not lift price more than the allowed move
        if (deltaRatio.signum() > 0 && move.compareTo(allowedMove) <= 0) {
            return deltaRatio.negate();
        }
        return BigDecimal.ZERO;
    }

    public record AbsorptionValue(
            BigDecimal strength,      // candle absorption: + buy absorption, - sell absorption, 0 none
            BigDecimal windowSum      // sum of strength over the last window candles
    ) {}
}
