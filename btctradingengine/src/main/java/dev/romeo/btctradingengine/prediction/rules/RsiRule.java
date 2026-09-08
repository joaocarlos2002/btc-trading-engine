package dev.romeo.btctradingengine.prediction.rules;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.prediction.SignalRule;

import java.math.BigDecimal;

public class RsiRule implements SignalRule {
    @Override
    public double evaluate(FeatureVector features) {
        BigDecimal rsi = features.rsiValue();

        if (rsi.compareTo(Config.getRsiOversold()) < 0) {
            // RSI < 30: sobrevenda forte â†’ BUY
            return 0.8;
        } else if (rsi.compareTo(Config.getRsiNeutralLow()) < 0) {
            // 30 <= RSI < 40: fraco para baixo
            return 0.3;
        } else if (rsi.compareTo(Config.getRsiNeutralHigh()) <= 0) {
            // 40 <= RSI <= 60: neutro
            return 0.0;
        } else if (rsi.compareTo(Config.getRsiOverbought()) <= 0) {
            // 60 < RSI <= 70: fraco para cima
            return -0.3;
        } else {
            // RSI > 70: sobrecompra forte â†’ SELL
            return -0.8;
        }
    }

    @Override
    public String getName() {
        return "RSI(" + Config.getRsiPeriod() + ")";
    }
}

