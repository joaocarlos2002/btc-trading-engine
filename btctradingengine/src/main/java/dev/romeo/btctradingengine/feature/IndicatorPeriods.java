package dev.romeo.btctradingengine.feature;

import dev.romeo.btctradingengine.indicator.VwapAnchor;

import java.math.BigDecimal;

/**
 * Every indicator setting FeatureExtractor needs, in one named object.
 *
 * It replaces the 11-argument constructor the on-demand backtests used to call: with the new
 * indicators that would have grown to ~17 positional ints, where swapping two adjacent periods
 * compiles cleanly and silently changes the strategy.
 *
 * All periods are expressed in candles, so they follow the same x15 scaling as the properties
 * file (defaults are for 1m candles).
 */
public record IndicatorPeriods(
        int sma, int ema, int rsi, int atr,
        int macdFast, int macdSlow, int macdSignal,
        int volatilityShort, int volatilityLong, int volumeAverage,
        int adx, int bollinger, BigDecimal bollingerStdDev,
        int mfi, int donchian,
        VwapAnchor vwapAnchor, int vwapRollingPeriods,
        int priceActionLookback, int priceActionSwingStrength,
        int cvd, int emaSlope,
        int vpinBuckets, int vpinBucketCandles,
        BigDecimal absorptionDeltaMin, BigDecimal absorptionVolumeRatioMin,
        BigDecimal absorptionMaxMoveAtr, int absorptionWindow
) {

    /** The periods application.properties ships (1m candles); for tests and tools that run without the application. */
    public static IndicatorPeriods defaults() {
        return new IndicatorPeriods(
                750, 390, 210, 210,
                180, 390, 135,
                75, 300, 300,
                210, 300, new BigDecimal("2.0"),
                210, 300,
                VwapAnchor.DAILY, 300,
                300, 30,
                300, 15,
                50, 20,
                new BigDecimal("0.3"), new BigDecimal("1.5"),
                new BigDecimal("0.25"), 15);
    }

    /** Overrides only the three periods the short FeatureExtractor constructor takes. */
    public IndicatorPeriods withCorePeriods(int smaPeriod, int emaPeriod, int rsiPeriod) {
        return new IndicatorPeriods(
                smaPeriod, emaPeriod, rsiPeriod, atr,
                macdFast, macdSlow, macdSignal,
                volatilityShort, volatilityLong, volumeAverage,
                adx, bollinger, bollingerStdDev,
                mfi, donchian,
                vwapAnchor, vwapRollingPeriods,
                priceActionLookback, priceActionSwingStrength,
                cvd, emaSlope,
                vpinBuckets, vpinBucketCandles,
                absorptionDeltaMin, absorptionVolumeRatioMin,
                absorptionMaxMoveAtr, absorptionWindow);
    }
}
