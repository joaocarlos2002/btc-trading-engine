package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.feature.IndicatorPeriods;
import dev.romeo.btctradingengine.indicator.VwapAnchor;

import java.math.BigDecimal;

/**
 * Every strategy knob that can be varied for an on-demand backtest without touching global
 * Config (which is static/shared and could affect a concurrently running live bot). Covers
 * indicator periods (FeatureExtractor), each rule's internal sub-thresholds, the entry
 * threshold/confirmation (RuleBasedPredictor), and the risk/backtest settings (BacktestEngine).
 *
 * The periods stay flat here on purpose: these component names are the /backtest query parameter
 * names, so renaming or nesting them would break the HTTP contract. indicatorPeriods() converts
 * them into the grouped object FeatureExtractor takes.
 */
public record BacktestParams(
        int smaPeriod,
        int emaPeriod,
        int rsiPeriod,
        int atrPeriod,
        int macdFastPeriod,
        int macdSlowPeriod,
        int macdSignalPeriod,
        int volatilityShortPeriods,
        int volatilityLongPeriods,
        int volumeAveragePeriods,
        int adxPeriod,
        int bollingerPeriod,
        BigDecimal bollingerStdDev,
        int mfiPeriod,
        int donchianPeriod,
        VwapAnchor vwapAnchor,
        int vwapRollingPeriods,

        BigDecimal rsiOversold,
        BigDecimal rsiNeutralLow,
        BigDecimal rsiNeutralHigh,
        BigDecimal rsiOverbought,
        BigDecimal smaDistanceExtreme,
        BigDecimal smaDistanceModerate,
        BigDecimal macdStrongHistogramAtrRatio,
        BigDecimal atrVolatilityLow,
        BigDecimal atrVolatilityNormal,
        BigDecimal atrVolatilityHigh,
        BigDecimal volatilityRatioHigh,
        BigDecimal mfiOversold,
        BigDecimal mfiNeutralLow,
        BigDecimal mfiNeutralHigh,
        BigDecimal mfiOverbought,
        BigDecimal adxTrendMin,
        BigDecimal bollingerSqueezeThreshold,

        double buyThreshold,
        double sellThreshold,
        int confirmationSnapshots,

        BigDecimal targetPercent,
        BigDecimal stopLossPercent,
        BigDecimal commissionRate
) {
    public static BacktestParams fromConfig() {
        return new BacktestParams(
                Config.getSmaPeriod(),
                Config.getEmaPeriod(),
                Config.getRsiPeriod(),
                Config.getAtrPeriod(),
                Config.getMacdFastPeriod(),
                Config.getMacdSlowPeriod(),
                Config.getMacdSignalPeriod(),
                Config.getVolatilityShortPeriods(),
                Config.getVolatilityLongPeriods(),
                Config.getVolumeAveragePeriods(),
                Config.getAdxPeriod(),
                Config.getBollingerPeriod(),
                Config.getBollingerStdDev(),
                Config.getMfiPeriod(),
                Config.getDonchianPeriod(),
                Config.getVwapAnchor(),
                Config.getVwapRollingPeriods(),

                Config.getRsiOversold(),
                Config.getRsiNeutralLow(),
                Config.getRsiNeutralHigh(),
                Config.getRsiOverbought(),
                Config.getSmaDistanceExtreme(),
                Config.getSmaDistanceModerate(),
                Config.getMacdStrongHistogramAtrRatio(),
                Config.getAtrVolatilityLow(),
                Config.getAtrVolatilityNormal(),
                Config.getAtrVolatilityHigh(),
                Config.getVolatilityRatioHigh(),
                Config.getMfiOversold(),
                Config.getMfiNeutralLow(),
                Config.getMfiNeutralHigh(),
                Config.getMfiOverbought(),
                Config.getAdxTrendMin(),
                Config.getBollingerSqueezeThreshold(),

                Config.getBuyThreshold(),
                Config.getSellThreshold(),
                Config.getConfirmationSnapshots(),

                Config.getTradingTargetPercent(),
                Config.getTradingStopLossPercent(),
                Config.getBacktestCommissionRate()
        );
    }

    public IndicatorPeriods indicatorPeriods() {
        return new IndicatorPeriods(
                smaPeriod, emaPeriod, rsiPeriod, atrPeriod,
                macdFastPeriod, macdSlowPeriod, macdSignalPeriod,
                volatilityShortPeriods, volatilityLongPeriods, volumeAveragePeriods,
                adxPeriod, bollingerPeriod, bollingerStdDev,
                mfiPeriod, donchianPeriod,
                vwapAnchor, vwapRollingPeriods);
    }
}
