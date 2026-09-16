package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.feature.FeatureVector;

public interface SignalRule {
    /**
     * Aplica a regra Ã s features e retorna um score [-1, +1]
     * +1: evidÃªncia forte de BUY
     *  0: neutro (HOLD)
     * -1: evidÃªncia forte de SELL
     */
    double evaluate(FeatureVector features);

    /**
     * Returns the name of the rule.
     */
    String getName();

    /**
     * Regime family of this rule (issue #7). With prediction.regime.gating.enabled the predictor
     * leaves out every rule whose family the current regime invalidates.
     */
    default RuleFamily family() {
        return RuleFamily.OTHER;
    }
}

