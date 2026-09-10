package dev.romeo.btctradingengine.prediction.rules;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.prediction.SignalRule;

import java.math.BigDecimal;

public class RsiRule implements SignalRule {
    private final BigDecimal oversold;
    private final BigDecimal neutralLow;
    private final BigDecimal neutralHigh;
    private final BigDecimal overbought;

    public RsiRule() {
        this(Config.getRsiOversold(), Config.getRsiNeutralLow(), Config.getRsiNeutralHigh(), Config.getRsiOverbought());
    }

    /** Allows overriding thresholds without touching global Config - used by on-demand backtests. */
    public RsiRule(BigDecimal oversold, BigDecimal neutralLow, BigDecimal neutralHigh, BigDecimal overbought) {
        this.oversold = oversold;
        this.neutralLow = neutralLow;
        this.neutralHigh = neutralHigh;
        this.overbought = overbought;
    }

    @Override
    public double evaluate(FeatureVector features) {
        BigDecimal rsi = features.rsiValue();

        if (rsi.compareTo(oversold) < 0) {
            // RSI < 30: sobrevenda forte â†’ BUY
            return 0.8;
        } else if (rsi.compareTo(neutralLow) < 0) {
            // 30 <= RSI < 40: fraco para baixo
            return 0.3;
        } else if (rsi.compareTo(neutralHigh) <= 0) {
            // 40 <= RSI <= 60: neutro
            return 0.0;
        } else if (rsi.compareTo(overbought) <= 0) {
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

