package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.feature.FeatureVector;

import java.math.BigDecimal;

/**
 * Execution filter on the order book (issue #10): do not open a BUY into a book dominated by asks, nor
 * a SELL into one dominated by bids. The issue asks for a filter, not a score vote - the imbalance of a
 * few top levels flips too fast to say where price is going, but it does say which side will be
 * expensive to cross right now.
 *
 * It never blocks without data: backtests have no order book, and neither do candles before the first
 * poll, so the imbalance is null there and every entry is allowed.
 */
public class OrderBookEntryGuard implements EntryGuard {
    private final boolean enabled;
    private final BigDecimal buyMin;
    private final BigDecimal sellMax;

    public OrderBookEntryGuard(boolean enabled, BigDecimal buyMin, BigDecimal sellMax) {
        this.enabled = enabled;
        this.buyMin = buyMin;
        this.sellMax = sellMax;
    }

    @Override
    public String blockReason(FeatureVector features, Signal signal) {
        BigDecimal imbalance = features.flow().orderBookImbalance();
        if (!enabled || imbalance == null) {
            return null;
        }
        if (signal == Signal.BUY && imbalance.compareTo(buyMin) < 0) {
            return "book bid share " + imbalance + " < " + buyMin;
        }
        if (signal == Signal.SELL && imbalance.compareTo(sellMax) > 0) {
            return "book bid share " + imbalance + " > " + sellMax;
        }
        return null;
    }
}
