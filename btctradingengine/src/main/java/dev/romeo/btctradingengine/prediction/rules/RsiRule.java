package dev.romeo.btctradingengine.prediction.rules;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.prediction.RuleFamily;
import dev.romeo.btctradingengine.prediction.SignalRule;

import java.math.BigDecimal;

public class RsiRule implements SignalRule {
    private final int period;
    private final BigDecimal oversold;
    private final BigDecimal neutralLow;
    private final BigDecimal neutralHigh;
    private final BigDecimal overbought;

    public RsiRule() {
        this(Config.getRsiPeriod(), Config.getRsiOversold(), Config.getRsiNeutralLow(), Config.getRsiNeutralHigh(),
                Config.getRsiOverbought());
    }

    /**
     * Allows overriding thresholds without touching global Config - used by on-demand backtests. The
     * period only labels the rule: it used to be read from Config, so a backtest with another period
     * reported the live one in its reason (issue #94).
     */
    public RsiRule(int period, BigDecimal oversold, BigDecimal neutralLow, BigDecimal neutralHigh, BigDecimal overbought) {
        this.period = period;
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
    public RuleFamily family() {
        return RuleFamily.MEAN_REVERSION;
    }

    @Override
    public String getName() {
        return "RSI(" + period + ")";
    }
}

