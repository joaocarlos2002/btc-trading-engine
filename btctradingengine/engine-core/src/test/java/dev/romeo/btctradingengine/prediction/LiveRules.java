package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.feature.IndicatorPeriods;
import dev.romeo.btctradingengine.prediction.rules.AdxRegimeRule;
import dev.romeo.btctradingengine.prediction.rules.AtrRule;
import dev.romeo.btctradingengine.prediction.rules.MacdRule;
import dev.romeo.btctradingengine.prediction.rules.MfiRule;
import dev.romeo.btctradingengine.prediction.rules.RsiRule;
import dev.romeo.btctradingengine.prediction.rules.SmaMomentumRule;
import dev.romeo.btctradingengine.prediction.rules.VolatilityRule;

/** The rules and predictor built from the shipped settings, as the no-arg Config constructors used to build them. */
final class LiveRules {
    static final PredictionSettings SETTINGS = PredictionSettings.defaults();
    static final IndicatorPeriods PERIODS = IndicatorPeriods.defaults();

    private LiveRules() {
    }

    static RuleBasedPredictor predictor(PredictionEventListener listener) {
        return new RuleBasedPredictor(listener, SETTINGS);
    }

    static RsiRule rsi() {
        return SETTINGS.rsiRule(PERIODS);
    }

    static SmaMomentumRule smaMomentum() {
        return SETTINGS.smaMomentumRule(PERIODS);
    }

    static MacdRule macd() {
        return SETTINGS.macdRule();
    }

    static MfiRule mfi() {
        return SETTINGS.mfiRule(PERIODS);
    }

    static AtrRule atr() {
        return SETTINGS.atrRule();
    }

    static VolatilityRule volatility() {
        return SETTINGS.volatilityRule();
    }

    static AdxRegimeRule adxRegime() {
        return SETTINGS.adxRegimeRule();
    }
}
