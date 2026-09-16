package dev.romeo.btctradingengine.prediction.rules;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.prediction.RuleFamily;
import dev.romeo.btctradingengine.prediction.SignalRule;

import java.math.BigDecimal;

/**
 * Directional rule on the Money Flow Index, the same shape as RsiRule: MFI is an oscillator with
 * volume built in, so it says more about exhaustion than RSI does on the same window.
 *
 * This one goes through addRule (it enters the score average), unlike the regime filters.
 */
public class MfiRule implements SignalRule {
    private final int period;
    private final BigDecimal oversold;
    private final BigDecimal neutralLow;
    private final BigDecimal neutralHigh;
    private final BigDecimal overbought;

    /** Allows overriding thresholds without touching global Config - used by on-demand backtests. The period only labels the rule. */
    public MfiRule(int period, BigDecimal oversold, BigDecimal neutralLow, BigDecimal neutralHigh, BigDecimal overbought) {
        this.period = period;
        this.oversold = oversold;
        this.neutralLow = neutralLow;
        this.neutralHigh = neutralHigh;
        this.overbought = overbought;
    }

    @Override
    public double evaluate(FeatureVector features) {
        BigDecimal mfi = features.flow().mfi();

        if (mfi.compareTo(oversold) < 0) {
            // MFI < 20: money flowing out with no buyers left -> BUY
            return 0.8;
        } else if (mfi.compareTo(neutralLow) < 0) {
            return 0.3;
        } else if (mfi.compareTo(neutralHigh) <= 0) {
            // Neutral band, which is also where the warmup value (50) lands
            return 0.0;
        } else if (mfi.compareTo(overbought) <= 0) {
            return -0.3;
        } else {
            // MFI > 80: buying pressure exhausted -> SELL
            return -0.8;
        }
    }

    @Override
    public RuleFamily family() {
        return RuleFamily.MEAN_REVERSION;
    }

    @Override
    public String getName() {
        return "MFI(" + period + ")";
    }
}
