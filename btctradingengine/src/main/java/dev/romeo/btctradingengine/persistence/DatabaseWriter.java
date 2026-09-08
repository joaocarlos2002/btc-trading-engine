package dev.romeo.btctradingengine.persistence;

import dev.romeo.btctradingengine.adapter.CandleEventListener;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class DatabaseWriter implements CandleEventListener, dev.romeo.btctradingengine.adapter.PriceEventListener {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseWriter.class);
    private static final int QUEUE_CAPACITY = 10000;
    private static final int BATCH_SIZE = 100;

    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread writerThread;

    public DatabaseWriter() {
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            writerThread = new Thread(this::writeLoop, "DatabaseWriter");
            writerThread.setDaemon(false);
            writerThread.start();
            logger.info("DatabaseWriter started");
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (writerThread != null) {
                try {
                    writerThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Interrupted waiting for writer thread", e);
                }
            }
            logger.info("DatabaseWriter stopped");
        }
    }

    @Override
    public void onEvent(CandleEvent event) {
        if (!queue.offer(event)) {
            logger.warn("DatabaseWriter queue full, dropping event: {}", event.openTime());
        }
    }

    @Override
    public void onEvent(NormalizedPriceEvent event) {
        if (!queue.offer(event)) {
            logger.warn("DatabaseWriter queue full, dropping tick: {}", event.eventTimestamp());
        }
    }

    private void writeLoop() {
        try {
            while (running.get()) {
                Object event = queue.poll();
                if (event instanceof CandleEvent candle) {
                    writeCandleToDatabase(candle);
                } else if (event instanceof NormalizedPriceEvent tick) {
                    writeTickToDatabase(tick);
                }
            }
            flushRemainingEvents();
        } catch (Exception e) {
            logger.error("Error in database writer loop", e);
        }
    }

    private void flushRemainingEvents() {
        while (!queue.isEmpty()) {
            Object event = queue.poll();
            if (event != null) {
                try {
                    if (event instanceof CandleEvent candle) {
                        writeCandleToDatabase(candle);
                    } else if (event instanceof NormalizedPriceEvent tick) {
                        writeTickToDatabase(tick);
                    }
                } catch (Exception e) {
                    logger.error("Error writing event during flush", e);
                }
            }
        }
    }

    private void writeCandleToDatabase(CandleEvent event) {
        String sql = "INSERT INTO candles (symbol, open_time_ms, close_time_ms, open, high, low, close, volume, tick_count) " +
                    "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ? " +
                    "WHERE NOT EXISTS (SELECT 1 FROM candles WHERE symbol = ? AND open_time_ms = ?)";

        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, event.instrument());
            stmt.setLong(2, event.openTime().toEpochMilli());
            stmt.setLong(3, event.closeTime().toEpochMilli());
            stmt.setBigDecimal(4, event.open());
            stmt.setBigDecimal(5, event.high());
            stmt.setBigDecimal(6, event.low());
            stmt.setBigDecimal(7, event.close());
            stmt.setBigDecimal(8, event.volume());
            stmt.setInt(9, event.tickCount());
            stmt.setString(10, event.instrument());
            stmt.setLong(11, event.openTime().toEpochMilli());

            int inserted = stmt.executeUpdate();
            if (inserted == 0) {
                logger.debug("Skipping duplicate candle: {} {}", event.instrument(), event.openTime());
            }

        } catch (SQLException e) {
            logger.error("Error persisting candle: {}", event.openTime(), e);
        }
    }

    private void writeTickToDatabase(NormalizedPriceEvent event) {
        String sql = "INSERT INTO ticks (symbol, time_ms, price, quantity) VALUES (?, ?, ?, ?)";

        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, event.instrument());
            stmt.setLong(2, event.eventTimestamp().toEpochMilli());
            stmt.setBigDecimal(3, event.price());
            stmt.setBigDecimal(4, event.quantity());
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error persisting tick: {}", event.eventTimestamp(), e);
        }
    }
}

