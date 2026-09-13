package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * ADX with +DI / -DI, used as a regime filter (trend vs. range), not as a directional score.
 *
 * Smoothing: this uses a plain SMA (SmaIncremental) instead of Wilder's smoothing, which is the
 * classic ADX formulation. That is a deliberate choice for internal consistency, not an oversight:
 * AtrIndicator and Rsi in this project are also SMA-based, and ADX is meant to be calibrated side
 * by side with ATR% (both feed the regime layer). Mixing Wilder here with SMA there would make the
 * two indicators respond on different effective horizons for the same configured period, so the
 * jointly calibrated regime thresholds would not be coherent. Values will therefore differ
 * slightly from a Wilder-based ADX on the same period.
 */
public class AdxIndicator {
    private final SmaIncremental trueRangeSma;
    private final SmaIncremental plusDmSma;
    private final SmaIncremental minusDmSma;
    private final SmaIncremental dxSma;

    private BigDecimal previousHigh;
    private BigDecimal previousLow;
    private BigDecimal previousClose;
    private boolean seeded = false;

    public AdxIndicator(int period) {
        this.trueRangeSma = new SmaIncremental(period);
        this.plusDmSma = new SmaIncremental(period);
        this.minusDmSma = new SmaIncremental(period);
        this.dxSma = new SmaIncremental(period);
    }

    public Optional<AdxValue> update(CandleEvent candle) {
        BigDecimal high = candle.high();
        BigDecimal low = candle.low();
        BigDecimal close = candle.close();

        if (!seeded) {
            seeded = true;
            previousHigh = high;
            previousLow = low;
            previousClose = close;
            return Optional.empty();
        }

        BigDecimal upMove = high.subtract(previousHigh);
        BigDecimal downMove = previousLow.subtract(low);

        BigDecimal plusDm = upMove.compareTo(downMove) > 0 && upMove.compareTo(BigDecimal.ZERO) > 0
                ? upMove
                : BigDecimal.ZERO;
        BigDecimal minusDm = downMove.compareTo(upMove) > 0 && downMove.compareTo(BigDecimal.ZERO) > 0
                ? downMove
                : BigDecimal.ZERO;

        BigDecimal trueRange = calculateTrueRange(high, low);

        previousHigh = high;
        previousLow = low;
        previousClose = close;

        Optional<BigDecimal> smoothedTrueRange = trueRangeSma.updateSum(trueRange);
        Optional<BigDecimal> smoothedPlusDm = plusDmSma.updateSum(plusDm);
        Optional<BigDecimal> smoothedMinusDm = minusDmSma.updateSum(minusDm);

        if (smoothedTrueRange.isEmpty() || smoothedPlusDm.isEmpty() || smoothedMinusDm.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal atr = smoothedTrueRange.orElse(BigDecimal.ZERO);
        BigDecimal plusDi = directionalIndex(smoothedPlusDm.orElse(BigDecimal.ZERO), atr);
        BigDecimal minusDi = directionalIndex(smoothedMinusDm.orElse(BigDecimal.ZERO), atr);

        BigDecimal dx = calculateDx(plusDi, minusDi);

        return dxSma.updateSum(dx)
                .map(adx -> new AdxValue(adx, plusDi, minusDi));
    }

    private BigDecimal calculateTrueRange(BigDecimal high, BigDecimal low) {
        BigDecimal highMinusLow = high.subtract(low);
        BigDecimal highMinusClose = high.subtract(previousClose).abs();
        BigDecimal lowMinusClose = low.subtract(previousClose).abs();

        return highMinusLow.max(highMinusClose).max(lowMinusClose);
    }

    /** Flat candles give a zero true range; without a divisor guard that would blow up here. */
    private BigDecimal directionalIndex(BigDecimal smoothedDm, BigDecimal atr) {
        if (atr.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return smoothedDm.multiply(BigDecimal.valueOf(100))
                .divide(atr, 8, RoundingMode.HALF_EVEN);
    }

    /** No directional movement at all (both DIs zero) means DX = 0, i.e. a perfectly flat regime. */
    private BigDecimal calculateDx(BigDecimal plusDi, BigDecimal minusDi) {
        BigDecimal diSum = plusDi.add(minusDi);
        if (diSum.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return plusDi.subtract(minusDi).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(diSum, 8, RoundingMode.HALF_EVEN);
    }

    public record AdxValue(
            BigDecimal adx,
            BigDecimal plusDi,
            BigDecimal minusDi
    ) {}
}
