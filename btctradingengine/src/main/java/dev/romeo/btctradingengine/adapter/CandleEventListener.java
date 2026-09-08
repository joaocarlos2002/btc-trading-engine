package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.model.CandleEvent;

@FunctionalInterface
public interface CandleEventListener {
    void onEvent(CandleEvent event);
}

