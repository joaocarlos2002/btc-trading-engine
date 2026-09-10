package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.config.Config;

/**
 * The subset of strategy knobs a backtest can vary without touching global Config:
 * indicator periods (feed FeatureExtractor) and the entry threshold/confirmation
 * (feed RuleBasedPredictor). Rule-internal sub-thresholds (RSI oversold, SMA distance,
 * ATR volatility bands, etc.) stay on Config since they don't depend on these periods.
 */
public record BacktestParams(
        int smaPeriod,
        int emaPeriod,
        int rsiPeriod,
        double buyThreshold,
        double sellThreshold,
        int confirmationSnapshots
) {
    public static BacktestParams fromConfig() {
        return new BacktestParams(
                Config.getSmaPeriod(),
                Config.getEmaPeriod(),
                Config.getRsiPeriod(),
                Config.getBuyThreshold(),
                Config.getSellThreshold(),
                Config.getConfirmationSnapshots()
        );
    }
}
