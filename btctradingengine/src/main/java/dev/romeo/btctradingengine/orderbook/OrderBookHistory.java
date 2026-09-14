package dev.romeo.btctradingengine.orderbook;

import dev.romeo.btctradingengine.feature.OrderBookLookup;
import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Order book imbalance snapshots and the rule that turns them into a candle's value (issue #10).
 *
 * A single snapshot is too noisy to use on its own - the top levels of BTCUSDT span a few thousandths
 * of a percent and change every few milliseconds - so a candle gets the mean of the snapshots taken
 * while it was open, in [openTime, closeTime). Snapshots from after the close are never included, and
 * a candle without any snapshot (warmup, backtests, a polling outage) gets null.
 */
public class OrderBookHistory implements OrderBookLookup {
    private static final int SCALE = 8;

    private final ConcurrentSkipListMap<Long, BigDecimal> imbalances = new ConcurrentSkipListMap<>();
    private final long retentionMillis;

    public OrderBookHistory(Duration retention) {
        this.retentionMillis = retention.toMillis();
    }

    public void add(Instant takenAt, BigDecimal imbalance) {
        imbalances.put(takenAt.toEpochMilli(), imbalance);
        if (retentionMillis > 0) {
            imbalances.headMap(imbalances.lastKey() - retentionMillis).clear();
        }
    }

    @Override
    public BigDecimal imbalanceFor(CandleEvent candle) {
        Map<Long, BigDecimal> window = imbalances.subMap(
                candle.openTime().toEpochMilli(), true, candle.closeTime().toEpochMilli(), false);
        if (window.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal imbalance : window.values()) {
            sum = sum.add(imbalance);
        }
        return sum.divide(BigDecimal.valueOf(window.size()), SCALE, RoundingMode.HALF_EVEN);
    }
}
