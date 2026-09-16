package dev.romeo.btctradingengine.port;

import dev.romeo.btctradingengine.adapter.PriceEventListener;

/**
 * Source of trade ticks (issue #111): the Binance WebSocket live, recorded ticks in a replay. The
 * pipeline after it (candles, features, prediction, positions) is the same for both.
 */
public interface MarketDataPort {
    void start(PriceEventListener listener);

    void stop();
}
