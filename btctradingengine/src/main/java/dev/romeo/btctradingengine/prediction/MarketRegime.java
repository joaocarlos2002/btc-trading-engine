package dev.romeo.btctradingengine.prediction;

/** Market regime a decision is made in (issue #7), see MarketRegimeClassifier. */
public enum MarketRegime {
    /** ADX still warming up: no opinion. */
    UNKNOWN,
    /** Bollinger bands compressed: a move is building, direction unknown. */
    SQUEEZE,
    /** ADX below prediction.adx.trend.min: no trend. */
    RANGE,
    /** ADX between trend.min and trend.strong, or +DI/-DI and the EMA slope disagree. */
    TRANSITION,
    TREND_UP,
    TREND_DOWN;

    /** A trend invalidates mean reversion, a range invalidates trend following; the rest allow both. */
    public boolean allows(RuleFamily family) {
        return switch (this) {
            case TREND_UP, TREND_DOWN -> family != RuleFamily.MEAN_REVERSION;
            case RANGE -> family != RuleFamily.TREND;
            default -> true;
        };
    }
}
