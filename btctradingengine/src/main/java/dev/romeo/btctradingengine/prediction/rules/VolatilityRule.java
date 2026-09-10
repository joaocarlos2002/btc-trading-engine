package dev.romeo.btctradingengine.prediction.rules;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.prediction.SignalRule;

import java.math.BigDecimal;

public class VolatilityRule implements SignalRule {
    private final BigDecimal ratioHigh;

    public VolatilityRule() {
        this(Config.getVolatilityRatioHigh());
    }

    /** Allows overriding the threshold without touching global Config - used by on-demand backtests. */
    public VolatilityRule(BigDecimal ratioHigh) {
        this.ratioHigh = ratioHigh;
    }

    @Override
    public double evaluate(FeatureVector features) {
        BigDecimal vol5m = features.volatility5m();
        BigDecimal vol20m = features.volatility20m();

        if (vol20m.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }

        BigDecimal ratio = vol5m.divide(vol20m, 4, java.math.RoundingMode.HALF_UP);

        if (ratio.compareTo(ratioHigh) > 0) {
            return 0.0;
        } else if (ratio.compareTo(BigDecimal.ONE) > 0) {
            return 0.1;
        } else {
            return -0.1;
        }
    }

    @Override
    public String getName() {
        return "Volatility-Ratio";
    }
}

