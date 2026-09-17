package dev.romeo.btctradingengine.prediction;

/**
 * Which kind of market a directional rule is valid in (issue #7). The regime gating in
 * RuleBasedPredictor drops a whole family when the current regime invalidates it.
 */
public enum RuleFamily {
    /** Follows the move: valid in trends, whipsawed in ranges (MACD). */
    TREND,
    /** Fades the move: valid in ranges, run over by trends (RSI, MFI, SMA distance). */
    MEAN_REVERSION,
    /** Not tied to a regime; never gated. Also the default for filter rules. */
    OTHER
}
