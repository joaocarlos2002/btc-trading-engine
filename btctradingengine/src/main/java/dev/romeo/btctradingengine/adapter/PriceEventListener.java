package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.model.NormalizedPriceEvent;

@FunctionalInterface
public interface PriceEventListener {
    void onEvent(NormalizedPriceEvent event);
}

