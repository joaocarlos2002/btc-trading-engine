package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;
import java.util.Optional;

public class AtrIndicator {
    private final SmaIncremental sma;
    private BigDecimal previousClose = BigDecimal.ZERO;

    public AtrIndicator(int period) {
        this.sma = new SmaIncremental(period);
    }

    public Optional<BigDecimal> update(CandleEvent candle) {
        BigDecimal high = candle.high();
        BigDecimal low = candle.low();
        BigDecimal close = candle.close();

        BigDecimal trueRange = calculateTrueRange(high, low);
        previousClose = close;

        return sma.updateSum(trueRange);
    }

    private BigDecimal calculateTrueRange(BigDecimal high, BigDecimal low) {
        BigDecimal highMinusLow = high.subtract(low);

        if (previousClose.compareTo(BigDecimal.ZERO) == 0) {
            return highMinusLow;
        }

        BigDecimal highMinusClose = high.subtract(previousClose).abs();
        BigDecimal lowMinusClose = low.subtract(previousClose).abs();

        return max(highMinusLow, highMinusClose, lowMinusClose);
    }

    private BigDecimal max(BigDecimal a, BigDecimal b, BigDecimal c) {
        return a.max(b).max(c);
    }
}

