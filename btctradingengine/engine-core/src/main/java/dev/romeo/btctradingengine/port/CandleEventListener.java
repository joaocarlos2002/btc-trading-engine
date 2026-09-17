package dev.romeo.btctradingengine.port;

import dev.romeo.btctradingengine.model.CandleEvent;

@FunctionalInterface
public interface CandleEventListener {
    void onEvent(CandleEvent event);
}

