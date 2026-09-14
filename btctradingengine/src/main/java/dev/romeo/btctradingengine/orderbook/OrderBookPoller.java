package dev.romeo.btctradingengine.orderbook;

import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.persistence.OrderBookSnapshotWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live feed of {@link OrderBookHistory}: polls the spot depth every orderbook.poll.seconds and writes
 * each snapshot to the database. A failed poll only leaves a gap - candles without snapshots get a
 * null imbalance, and the entry guard never blocks on missing data.
 */
public class OrderBookPoller {
    private static final Logger logger = LoggerFactory.getLogger(OrderBookPoller.class);

    private final BinanceDepthClient client;
    private final OrderBookHistory history;
    private final OrderBookSnapshotWriter writer;
    private final String symbol;
    private final int levels;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "OrderBookPoller");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean failing;

    public OrderBookPoller(BinanceDepthClient client, OrderBookHistory history, OrderBookSnapshotWriter writer,
                           String symbol, int levels) {
        this.client = client;
        this.history = history;
        this.writer = writer;
        this.symbol = symbol;
        this.levels = levels;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::poll, 0, Config.getOrderBookPollSeconds(), TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    /** Logged only when the state changes, since this runs every few seconds. */
    private void poll() {
        try {
            Instant takenAt = Instant.now();
            BinanceDepthClient.DepthSnapshot snapshot = client.fetch(symbol, levels);
            BigDecimal imbalance = snapshot.imbalance();
            if (imbalance != null) {
                history.add(takenAt, imbalance);
            }
            writer.write(symbol, takenAt, snapshot);
            if (failing) {
                logger.info("Order book polling recovered");
                failing = false;
            }
        } catch (Exception e) {
            if (!failing) {
                logger.warn("Order book polling failed, imbalance unavailable until it recovers: {}", e.getMessage());
                failing = true;
            }
        }
    }
}
