package dev.romeo.btctradingengine.port;

import dev.romeo.btctradingengine.model.NormalizedPriceEvent;

@FunctionalInterface
public interface PriceEventListener {
    void onEvent(NormalizedPriceEvent event);
}

