package dev.romeo.btctradingengine.feature;

import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;

/**
 * Supplies the order book imbalance of a candle (issue #10). The book is polled outside the candle
 * stream, so FeatureExtractor looks it up per candle, the same way it does for derivatives.
 */
@FunctionalInterface
public interface OrderBookLookup {

    OrderBookLookup NONE = candle -> null;

    /** Mean bid share [0-1] of the book snapshots taken while the candle was open, or null when there were none. */
    BigDecimal imbalanceFor(CandleEvent candle);
}
