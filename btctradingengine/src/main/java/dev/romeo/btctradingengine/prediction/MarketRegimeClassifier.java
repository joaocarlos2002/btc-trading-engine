package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.feature.RegimeFeatures;

import java.math.BigDecimal;

/**
 * Classifies the market before any rule votes (issue #7). Uses the same thresholds AdxRegimeRule
 * already had, so both agree on what "no trend" and "squeeze" mean.
 *
 * A trend needs three things to agree: ADX strong enough, +DI/-DI pointing one way, and the EMA slope
 * not pointing the other way. ADX alone only measures strength, not direction, and a DI crossover
 * against the EMA slope is usually the turn itself - that reads as TRANSITION, where nothing is gated.
 */
public class MarketRegimeClassifier {
    private final BigDecimal trendMin;
    private final BigDecimal trendStrong;
    private final BigDecimal squeezeThreshold;

    public MarketRegimeClassifier(BigDecimal trendMin, BigDecimal trendStrong, BigDecimal squeezeThreshold) {
        this.trendMin = trendMin;
        this.trendStrong = trendStrong;
        this.squeezeThreshold = squeezeThreshold;
    }

    public static MarketRegimeClassifier fromConfig() {
        return new MarketRegimeClassifier(
                Config.getAdxTrendMin(), Config.getAdxTrendStrong(), Config.getBollingerSqueezeThreshold());
    }

    public MarketRegime classify(FeatureVector features) {
        RegimeFeatures regime = features.regime();
        BigDecimal adx = regime.adx();
        BigDecimal bbWidth = regime.bbWidth();

        // Zero means the indicator is still warming up, the same convention AdxRegimeRule uses
        if (adx.compareTo(BigDecimal.ZERO) <= 0) {
            return MarketRegime.UNKNOWN;
        }
        if (bbWidth.compareTo(BigDecimal.ZERO) > 0 && bbWidth.compareTo(squeezeThreshold) < 0) {
            return MarketRegime.SQUEEZE;
        }
        if (adx.compareTo(trendMin) < 0) {
            return MarketRegime.RANGE;
        }
        if (adx.compareTo(trendStrong) < 0) {
            return MarketRegime.TRANSITION;
        }

        int direction = regime.plusDi().compareTo(regime.minusDi());
        int slope = regime.emaSlope().signum();
        if (direction > 0 && slope >= 0) {
            return MarketRegime.TREND_UP;
        }
        if (direction < 0 && slope <= 0) {
            return MarketRegime.TREND_DOWN;
        }
        return MarketRegime.TRANSITION;
    }
}
