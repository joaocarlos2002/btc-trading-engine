package dev.romeo.btctradingengine.marketstate;

import dev.romeo.btctradingengine.model.NormalizedPriceEvent;

import java.util.ArrayDeque;

import java.util.ArrayList;
import java.util.List;

public class MarketState {
    private final int n;

    private final ArrayDeque<NormalizedPriceEvent> queue = new ArrayDeque<>();

    public MarketState(int n) {
        this.n = n;
    }

    public synchronized void add(NormalizedPriceEvent event) {
        if (queue.size() >= n) {
            queue.removeFirst();
        }
        queue.addLast(event);
    }

    public synchronized List<NormalizedPriceEvent> getEvents() {
        return new ArrayList<>(queue);
    }
}

