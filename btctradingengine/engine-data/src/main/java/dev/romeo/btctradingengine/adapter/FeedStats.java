package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.model.NormalizedPriceEvent;

import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Counters of the market data feed, read by the metrics and the health check (issue #104).
 *
 * <p>{@link #record} runs on the WebSocket reader for every tick, before the tick is handed to the
 * PriceEventBus, so it only writes primitives: no allocation, no lock. Lag is the tick's receipt time
 * minus Binance's trade time, which includes network delay and any clock skew between the two hosts.
 */
public class FeedStats {
    private final LongSupplier clockMillis;
    private final LongAdder ticks = new LongAdder();
    private final long createdAtMillis;
    private volatile long lastLagMillis;
    private volatile long lastReceiptMillis;

    public FeedStats() {
        this(System::currentTimeMillis);
    }

    FeedStats(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
        this.createdAtMillis = clockMillis.getAsLong();
    }

    public void record(NormalizedPriceEvent event) {
        ticks.increment();
        long receipt = event.receiptTimestamp() != null
                ? event.receiptTimestamp().toEpochMilli()
                : clockMillis.getAsLong();
        if (event.eventTimestamp() != null) {
            lastLagMillis = receipt - event.eventTimestamp().toEpochMilli();
        }
        lastReceiptMillis = receipt;
    }

    public long ticksTotal() {
        return ticks.sum();
    }

    /** Lag of the most recent tick in milliseconds; 0 before the first tick. */
    public long lastLagMillis() {
        return lastLagMillis;
    }

    /** True once at least one tick arrived. */
    public boolean hasReceivedTicks() {
        return lastReceiptMillis != 0;
    }

    /** Milliseconds since the last tick arrived, or since this object was created when none did yet. */
    public long millisSinceLastTick() {
        long last = lastReceiptMillis;
        return clockMillis.getAsLong() - (last != 0 ? last : createdAtMillis);
    }
}
