package dev.romeo.btctradingengine.feature;

import dev.romeo.btctradingengine.config.Config;
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
        VwapAnchor vwapAnchor, int vwapRollingPeriods
) {

    public static IndicatorPeriods fromConfig() {
        return new IndicatorPeriods(
                Config.getSmaPeriod(), Config.getEmaPeriod(), Config.getRsiPeriod(), Config.getAtrPeriod(),
                Config.getMacdFastPeriod(), Config.getMacdSlowPeriod(), Config.getMacdSignalPeriod(),
                Config.getVolatilityShortPeriods(), Config.getVolatilityLongPeriods(), Config.getVolumeAveragePeriods(),
                Config.getAdxPeriod(), Config.getBollingerPeriod(), Config.getBollingerStdDev(),
                Config.getMfiPeriod(), Config.getDonchianPeriod(),
                Config.getVwapAnchor(), Config.getVwapRollingPeriods());
    }

    /** Overrides only the three periods the short FeatureExtractor constructor takes. */
    public IndicatorPeriods withCorePeriods(int smaPeriod, int emaPeriod, int rsiPeriod) {
        return new IndicatorPeriods(
                smaPeriod, emaPeriod, rsiPeriod, atr,
                macdFast, macdSlow, macdSignal,
                volatilityShort, volatilityLong, volumeAverage,
                adx, bollinger, bollingerStdDev,
                mfi, donchian,
                vwapAnchor, vwapRollingPeriods);
    }
}
