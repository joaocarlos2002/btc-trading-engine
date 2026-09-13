package dev.romeo.btctradingengine.prediction.rules;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.prediction.SignalRule;

import java.math.BigDecimal;

/**
 * Regime veto: ADX (trend strength) plus Bollinger Width (squeeze). Registered through
 * addFilterRule, never addRule - a weak trend must not drag the directional score down, it must
 * disable the signals that need a trend. Feeding ADX into the score average would penalise range
 * signals exactly in the range regime where they are the valid ones.
 *
 * Scores are deliberately kept inside [-0.3, 0], the same band AtrRule and VolatilityRule use.
 * The filter average only has to cross zero to block an entry, and a large magnitude here would
 * let one filter drown out the others (see the addFilterRule comment in RuleBasedPredictor).
 *
 * Only half of the regime logic is expressible today. "No trend disables breakout signals" is a
 * veto and is implemented. "A strong trend disables mean-reversion signals" is not: a SignalRule
 * returns one scalar for the whole prediction, so it cannot veto the mean-reversion rules (RSI,
 * MFI) while leaving the trend rules (SMA, MACD) alone. That needs per-family scores in
 * RuleBasedPredictor, so a strong trend returns a neutral 0 here instead of blocking entries that
 * are probably the good ones. prediction.adx.trend.strong therefore has no consumer yet - it stays
 * in the properties file (and is range-checked by Config.validate) waiting for that split.
 */
public class AdxRegimeRule implements SignalRule {
    private static final double NO_TREND_PENALTY = -0.3;
    private static final double SQUEEZE_PENALTY = -0.2;

    private final BigDecimal trendMin;
    private final BigDecimal squeezeThreshold;

    public AdxRegimeRule() {
        this(Config.getAdxTrendMin(), Config.getBollingerSqueezeThreshold());
    }

    /** Allows overriding thresholds without touching global Config - used by on-demand backtests. */
    public AdxRegimeRule(BigDecimal trendMin, BigDecimal squeezeThreshold) {
        this.trendMin = trendMin;
        this.squeezeThreshold = squeezeThreshold;
    }

    @Override
    public double evaluate(FeatureVector features) {
        BigDecimal adx = features.regime().adx();
        BigDecimal bbWidth = features.regime().bbWidth();

        double score = 0.0;

        // Zero means the indicator is still warming up: no opinion, do not veto.
        if (adx.compareTo(BigDecimal.ZERO) > 0 && adx.compareTo(trendMin) < 0) {
            score = NO_TREND_PENALTY;
        }
        if (bbWidth.compareTo(BigDecimal.ZERO) > 0 && bbWidth.compareTo(squeezeThreshold) < 0) {
            // Take the worse of the two rather than adding them up, to stay inside the band.
            score = Math.min(score, SQUEEZE_PENALTY);
        }

        return score;
    }

    @Override
    public String getName() {
        return "Regime-ADX+BBWidth";
    }
}
