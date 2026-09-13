package dev.romeo.btctradingengine.persistence;

import dev.romeo.btctradingengine.adapter.CandleEventListener;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DatabaseWriter implements CandleEventListener, dev.romeo.btctradingengine.adapter.PriceEventListener {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseWriter.class);
    private static final int QUEUE_CAPACITY = 10000;
    private static final int BATCH_SIZE = 100;
    private static final long POLL_TIMEOUT_MS = 100;

    private static final String INSERT_CANDLE_SQL =
            "INSERT INTO candles (symbol, open_time_ms, close_time_ms, open, high, low, close, volume, tick_count) " +
            "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ? " +
            "WHERE NOT EXISTS (SELECT 1 FROM candles WHERE symbol = ? AND open_time_ms = ?)";
    private static final String INSERT_TICK_SQL =
            "INSERT INTO ticks (symbol, time_ms, price, quantity) VALUES (?, ?, ?, ?)";

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
        List<Object> batch = new ArrayList<>(BATCH_SIZE);
        try {
            while (running.get()) {
                Object first = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                writeBatchSafely(batch);
                batch.clear();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("DatabaseWriter interrupted, {} events pending", batch.size() + queue.size());
        } catch (Throwable t) {
            // Per-batch RuntimeExceptions are handled in writeBatchSafely; only Errors reach here.
            logger.error("DatabaseWriter loop terminated unexpectedly, {} events pending",
                    batch.size() + queue.size(), t);
            throw t;
        } finally {
            // Events already drained but not yet written go first.
            writeBatchSafely(batch);
            flushRemainingEvents();
        }
    }

    private void flushRemainingEvents() {
        int pending = queue.size();
        if (pending > 0) {
            logger.info("Flushing {} pending events", pending);
        }
        List<Object> batch = new ArrayList<>(BATCH_SIZE);
        while (queue.drainTo(batch, BATCH_SIZE) > 0) {
            writeBatchSafely(batch);
            batch.clear();
        }
    }

    private void writeBatchSafely(List<Object> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            if (!writeBatch(batch)) {
                writeIndividually(batch);
            }
        } catch (RuntimeException e) {
            logger.error("Unexpected error writing batch of {} events", batch.size(), e);
        }
    }

    /**
     * Writes the whole batch in a single transaction on one connection.
     *
     * @return false if the batch was rolled back or never written, so the caller can retry
     *         event by event and one bad row does not drop the others
     */
    private boolean writeBatch(List<Object> batch) {
        try (Connection conn = DataSourceManager.getDataSource().getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement candleStmt = conn.prepareStatement(INSERT_CANDLE_SQL);
                 PreparedStatement tickStmt = conn.prepareStatement(INSERT_TICK_SQL)) {

                int candles = 0;
                int ticks = 0;
                for (Object event : batch) {
                    if (event instanceof CandleEvent candle) {
                        bindCandle(candleStmt, candle);
                        candleStmt.addBatch();
                        candles++;
                    } else if (event instanceof NormalizedPriceEvent tick) {
                        bindTick(tickStmt, tick);
                        tickStmt.addBatch();
                        ticks++;
                    }
                }

                if (candles > 0) {
                    int[] results = candleStmt.executeBatch();
                    for (int result : results) {
                        if (result == 0) {
                            logger.debug("Skipping duplicate candle in batch");
                        }
                    }
                }
                if (ticks > 0) {
                    tickStmt.executeBatch();
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                logger.warn("Batch write of {} events failed, retrying individually", batch.size(), e);
                try {
                    conn.rollback();
                } catch (SQLException rollbackError) {
                    logger.warn("Rollback of failed batch also failed", rollbackError);
                }
                return false;
            } finally {
                try {
                    conn.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // Connection is likely broken; Hikari will evict it on return.
                }
            }
        } catch (SQLException e) {
            logger.warn("Error acquiring connection for batch of {} events, retrying individually", batch.size(), e);
            return false;
        }
    }

    private void writeIndividually(List<Object> batch) {
        for (Object event : batch) {
            if (event instanceof CandleEvent candle) {
                writeCandleToDatabase(candle);
            } else if (event instanceof NormalizedPriceEvent tick) {
                writeTickToDatabase(tick);
            }
        }
    }

    private void writeCandleToDatabase(CandleEvent event) {
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_CANDLE_SQL)) {

            bindCandle(stmt, event);
            int inserted = stmt.executeUpdate();
            if (inserted == 0) {
                logger.debug("Skipping duplicate candle: {} {}", event.instrument(), event.openTime());
            }

        } catch (SQLException e) {
            logger.error("Error persisting candle: {}", event.openTime(), e);
        }
    }

    private void writeTickToDatabase(NormalizedPriceEvent event) {
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_TICK_SQL)) {

            bindTick(stmt, event);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error persisting tick: {}", event.eventTimestamp(), e);
        }
    }

    private static void bindCandle(PreparedStatement stmt, CandleEvent event) throws SQLException {
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
    }

    private static void bindTick(PreparedStatement stmt, NormalizedPriceEvent event) throws SQLException {
        stmt.setString(1, event.instrument());
        stmt.setLong(2, event.eventTimestamp().toEpochMilli());
        stmt.setBigDecimal(3, event.price());
        stmt.setBigDecimal(4, event.quantity());
    }
}
