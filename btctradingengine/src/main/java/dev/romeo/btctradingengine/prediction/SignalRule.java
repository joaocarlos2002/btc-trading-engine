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
     * Nome legÃ­vel da regra (pra logging/debugging)
     */
    String getName();
}

