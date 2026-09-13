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
 * Two modes, following prediction.regime.gating.enabled (issue #7):
 * <ul>
 *   <li>gating off: low ADX vetoes every entry. It is the only way to keep trend signals out of a
 *       range, at the cost of also blocking the mean-reversion signals that are valid there.</li>
 *   <li>gating on: RuleBasedPredictor already drops the trend family in a range and the
 *       mean-reversion family in a trend, so the low-ADX veto is skipped - it would otherwise block
 *       exactly the mean-reversion entries the gating keeps.</li>
 * </ul>
 * The squeeze veto applies in both modes: no family is reliable before the breakout.
 */
public class AdxRegimeRule implements SignalRule {
    private static final double NO_TREND_PENALTY = -0.3;
    private static final double SQUEEZE_PENALTY = -0.2;

    private final BigDecimal trendMin;
    private final BigDecimal squeezeThreshold;
    private final boolean regimeGatingEnabled;

    public AdxRegimeRule() {
        this(Config.getAdxTrendMin(), Config.getBollingerSqueezeThreshold(), Config.isRegimeGatingEnabled());
    }

    /** Allows overriding thresholds without touching global Config - used by on-demand backtests. */
    public AdxRegimeRule(BigDecimal trendMin, BigDecimal squeezeThreshold) {
        this(trendMin, squeezeThreshold, false);
    }

    public AdxRegimeRule(BigDecimal trendMin, BigDecimal squeezeThreshold, boolean regimeGatingEnabled) {
        this.trendMin = trendMin;
        this.squeezeThreshold = squeezeThreshold;
        this.regimeGatingEnabled = regimeGatingEnabled;
    }

    @Override
    public double evaluate(FeatureVector features) {
        BigDecimal adx = features.regime().adx();
        BigDecimal bbWidth = features.regime().bbWidth();

        double score = 0.0;

        // Zero means the indicator is still warming up: no opinion, do not veto.
        if (!regimeGatingEnabled && adx.compareTo(BigDecimal.ZERO) > 0 && adx.compareTo(trendMin) < 0) {
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
