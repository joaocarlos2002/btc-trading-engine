package dev.romeo.btctradingengine.prediction.rules;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.prediction.SignalRule;

import java.math.BigDecimal;

public class SmaMomentumRule implements SignalRule {
    private final BigDecimal distanceExtreme;
    private final BigDecimal distanceModerate;

    public SmaMomentumRule() {
        this(Config.getSmaDistanceExtreme(), Config.getSmaDistanceModerate());
    }

    /** Allows overriding thresholds without touching global Config - used by on-demand backtests. */
    public SmaMomentumRule(BigDecimal distanceExtreme, BigDecimal distanceModerate) {
        this.distanceExtreme = distanceExtreme;
        this.distanceModerate = distanceModerate;
    }

    @Override
    public double evaluate(FeatureVector features) {
        BigDecimal smaDistance = features.smaDistance();

        // smaDistance em % de desvio da SMA configurada
        // Positivo: acima da SMA (bullish)
        // Negativo: abaixo da SMA (bearish)

        if (smaDistance.compareTo(distanceExtreme) > 0) {
            // Muito acima da SMA (possÃ­vel reversÃ£o para baixo)
            return -0.5;
        } else if (smaDistance.compareTo(distanceModerate) > 0) {
            // Moderadamente acima (manutenÃ§Ã£o)
            return 0.2;
        } else if (smaDistance.compareTo(distanceModerate.negate()) >= 0) {
            // Perto da SMA (neutro)
            return 0.0;
        } else if (smaDistance.compareTo(distanceExtreme.negate()) > 0) {
            // Moderadamente abaixo (possÃ­vel reversÃ£o para cima)
            return 0.3;
        } else {
            // Muito abaixo da SMA (compra agressiva)
            return 0.7;
        }
    }

    @Override
    public String getName() {
        return "SMA" + Config.getSmaPeriod() + "-Distance";
    }
}

