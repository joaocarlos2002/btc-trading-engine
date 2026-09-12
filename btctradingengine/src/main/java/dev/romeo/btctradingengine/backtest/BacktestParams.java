package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.config.Config;

import java.math.BigDecimal;

/**
 * Every strategy knob that can be varied for an on-demand backtest without touching global
 * Config (which is static/shared and could affect a concurrently running live bot). Covers
 * indicator periods (FeatureExtractor), each rule's internal sub-thresholds, the entry
 * threshold/confirmation (RuleBasedPredictor), and the risk/backtest settings (BacktestEngine).
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

                Config.getBuyThreshold(),
                Config.getSellThreshold(),
                Config.getConfirmationSnapshots(),

                Config.getTradingTargetPercent(),
                Config.getTradingStopLossPercent(),
                Config.getBacktestCommissionRate()
        );
    }
}
