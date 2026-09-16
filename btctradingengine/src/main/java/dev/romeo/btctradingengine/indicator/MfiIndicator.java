package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Money Flow Index: RSI-like oscillator on typical price weighted by volume.
 *
 * This is the one new indicator that is directional enough to feed the score average (see
 * MfiRule) - it carries volume, so it is more informative than plain RSI.
 *
 * Raw money flow is typicalPrice((H+L+C)/3) * volume, classified as positive or negative by the
 * direction of the typical price against the previous candle's. Unchanged typical price counts as
 * neither, which is the classic definition.
 *
 * Smoothing: plain sums over the window, the same SMA-style approach used by Rsi and
 * AtrIndicator in this project - a deliberate consistency choice, so MFI and RSI stay comparable
 * on the same configured period.
 */
public class MfiIndicator {
    private final int period;
    private final Deque<BigDecimal> positiveFlows = new ArrayDeque<>();
    private final Deque<BigDecimal> negativeFlows = new ArrayDeque<>();

    /** Running sums over the two deques (issue #95), kept exact in BigDecimal instead of re-summed. */
    private BigDecimal positiveSum = BigDecimal.ZERO;
    private BigDecimal negativeSum = BigDecimal.ZERO;

    private BigDecimal previousTypicalPrice;
    private boolean seeded = false;

    public MfiIndicator(int period) {
        this.period = period;
    }

    public Optional<BigDecimal> update(CandleEvent candle) {
        BigDecimal typicalPrice = typicalPrice(candle);

        if (!seeded) {
            seeded = true;
            previousTypicalPrice = typicalPrice;
            return Optional.empty();
        }

        BigDecimal rawMoneyFlow = typicalPrice.multiply(candle.volume());
        int direction = typicalPrice.compareTo(previousTypicalPrice);
        previousTypicalPrice = typicalPrice;

        BigDecimal positiveFlow = direction > 0 ? rawMoneyFlow : BigDecimal.ZERO;
        BigDecimal negativeFlow = direction < 0 ? rawMoneyFlow : BigDecimal.ZERO;
        positiveFlows.addLast(positiveFlow);
        negativeFlows.addLast(negativeFlow);
        positiveSum = positiveSum.add(positiveFlow);
        negativeSum = negativeSum.add(negativeFlow);

        if (positiveFlows.size() > period) {
            positiveSum = positiveSum.subtract(positiveFlows.removeFirst());
            negativeSum = negativeSum.subtract(negativeFlows.removeFirst());
        }
        if (positiveFlows.size() < period) {
            return Optional.empty();
        }

        return Optional.of(moneyFlowIndex(positiveSum, negativeSum));
    }

    private BigDecimal moneyFlowIndex(BigDecimal positiveSum, BigDecimal negativeSum) {
        if (negativeSum.compareTo(BigDecimal.ZERO) == 0) {
            // No negative flow at all. With positive flow present this is the saturated overbought
            // case and reports 100, mirroring how Rsi handles a zero loss sum. With no flow either
            // way (a perfectly flat window, or zero volume) there is no pressure to report, so it
            // reports the neutral 50 instead - saturating to 100 there would hand MfiRule a strong
            // SELL on a dead market.
            return positiveSum.compareTo(BigDecimal.ZERO) > 0
                    ? BigDecimal.valueOf(100)
                    : BigDecimal.valueOf(50);
        }

        BigDecimal moneyRatio = positiveSum.divide(negativeSum, 8, RoundingMode.HALF_EVEN);

        return BigDecimal.valueOf(100).subtract(BigDecimal.valueOf(100)
                .divide(BigDecimal.ONE.add(moneyRatio), 8, RoundingMode.HALF_EVEN));
    }

    private BigDecimal typicalPrice(CandleEvent candle) {
        return candle.high()
                .add(candle.low())
                .add(candle.close())
                .divide(BigDecimal.valueOf(3), 8, RoundingMode.HALF_EVEN);
    }
}
