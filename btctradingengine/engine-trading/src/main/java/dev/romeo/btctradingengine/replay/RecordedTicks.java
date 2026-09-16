package dev.romeo.btctradingengine.replay;

import dev.romeo.btctradingengine.port.PriceEventListener;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.port.MarketDataPort;

import java.util.List;

/** Recorded ticks as a market data source: delivered in order, synchronously, on the caller's thread. */
public class RecordedTicks implements MarketDataPort {
    private final List<NormalizedPriceEvent> ticks;
    private volatile boolean stopped;

    public RecordedTicks(List<NormalizedPriceEvent> ticks) {
        this.ticks = List.copyOf(ticks);
    }

    @Override
    public void start(PriceEventListener listener) {
        stopped = false;
        for (NormalizedPriceEvent tick : ticks) {
            if (stopped) {
                return;
            }
            listener.onEvent(tick);
        }
    }

    @Override
    public void stop() {
        stopped = true;
    }

    public int size() {
        return ticks.size();
    }
}
