package dev.romeo.btctradingengine.prediction.rules;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.prediction.SignalRule;

import java.math.BigDecimal;

public class AtrRule implements SignalRule {
    private final BigDecimal volatilityLow;
    private final BigDecimal volatilityNormal;
    private final BigDecimal volatilityHigh;

    /** Allows overriding thresholds without touching global Config - used by on-demand backtests. */
    public AtrRule(BigDecimal volatilityLow, BigDecimal volatilityNormal, BigDecimal volatilityHigh) {
        this.volatilityLow = volatilityLow;
        this.volatilityNormal = volatilityNormal;
        this.volatilityHigh = volatilityHigh;
    }

    @Override
    public double evaluate(FeatureVector features) {
        // ATR% is computed once in FeatureExtractor and carried as a regime feature instead of
        // being recalculated here - more than one filter rule consumes the same value.
        BigDecimal atrPercent = features.regime().atrPercent();

        if (atrPercent.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }


        if (atrPercent.compareTo(volatilityLow) < 0) {
            return -0.3;
        } else if (atrPercent.compareTo(volatilityNormal) < 0) {
            return 0.0;
        } else if (atrPercent.compareTo(volatilityHigh) < 0) {
            return 0.1;
        } else {
            return 0.2;
        }
    }

    @Override
    public String getName() {
        return "ATR-Volatility";
    }
}

