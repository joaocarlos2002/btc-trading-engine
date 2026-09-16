package dev.romeo.btctradingengine.persistence;

import dev.romeo.btctradingengine.adapter.CandleEventListener;
import dev.romeo.btctradingengine.model.AggressorSide;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.model.TradeFlow;
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
    private static final long STOP_TIMEOUT_MS = 5000;
    private static final long INTERRUPT_GRACE_MS = 1000;

    private static final String INSERT_CANDLE_SQL =
            "INSERT INTO candles (symbol, open_time_ms, close_time_ms, open, high, low, close, volume, tick_count, " +
            "taker_buy_volume, large_buy_volume, large_sell_volume, flow_source) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (symbol, open_time_ms) DO NOTHING";
    private static final String INSERT_TICK_SQL =
            "INSERT INTO ticks (symbol, time_ms, price, quantity, aggressor_side) VALUES (?, ?, ?, ?, ?)";

    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread writerThread;

    public DatabaseWriter() {
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            writerThread = new Thread(this::writeLoop, "DatabaseWriter");
            // Daemon so this thread can never keep the JVM alive. Flushing on shutdown is stop()'s job,
            // called from the shutdown hook, not something the daemon flag can guarantee.
            writerThread.setDaemon(true);
            writerThread.start();
            logger.info("DatabaseWriter started");
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (writerThread != null) {
                try {
                    writerThread.join(STOP_TIMEOUT_MS);
                    if (writerThread.isAlive()) {
                        logger.warn("DatabaseWriter did not finish within {} ms, interrupting; {} events pending",
                                STOP_TIMEOUT_MS, queue.size());
                        writerThread.interrupt();
                        writerThread.join(INTERRUPT_GRACE_MS);
                    }
                    if (writerThread.isAlive()) {
                        // Typically blocked in a JDBC socket read, which interrupt() cannot break.
                        logger.error("DatabaseWriter still blocked after interrupt; abandoning it with {} events pending",
                                queue.size());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    writerThread.interrupt();
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
            logger.warn("DatabaseWriter interrupted while waiting for events");
        } catch (Throwable t) {
            // Per-batch RuntimeExceptions are handled in writeBatchSafely; only Errors reach here.
            logger.error("DatabaseWriter loop terminated unexpectedly, {} events pending",
                    batch.size() + queue.size(), t);
            throw t;
        } finally {
            if (Thread.currentThread().isInterrupted()) {
                // Only stop() interrupts us, after its timeout: give up instead of writing against a closing pool.
                discardPending(batch);
            } else {
                // Events already drained but not yet written go first.
                writeBatchSafely(batch);
                flushRemainingEvents();
            }
        }
    }

    private void discardPending(List<Object> batch) {
        int discarded = batch.size() + queue.size();
        batch.clear();
        queue.clear();
        if (discarded > 0) {
            logger.error("DatabaseWriter interrupted during shutdown, discarding {} unwritten events", discarded);
        }
    }

    private void flushRemainingEvents() {
        int pending = queue.size();
        if (pending > 0) {
            logger.info("Flushing {} pending events", pending);
        }
        List<Object> batch = new ArrayList<>(BATCH_SIZE);
        while (!Thread.currentThread().isInterrupted() && queue.drainTo(batch, BATCH_SIZE) > 0) {
            writeBatchSafely(batch);
            batch.clear();
        }
        if (Thread.currentThread().isInterrupted()) {
            discardPending(batch);
        }
    }

    private void writeBatchSafely(List<Object> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            if (!writeBatch(batch)) {
                if (Thread.currentThread().isInterrupted()) {
                    logger.error("Discarding batch of {} events: writer interrupted during shutdown", batch.size());
                } else {
                    writeIndividually(batch);
                }
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
        for (int i = 0; i < batch.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                logger.error("Discarding {} events: writer interrupted during shutdown", batch.size() - i);
                return;
            }
            Object event = batch.get(i);
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

        // NULL, not 0, when the split is unknown - 0 would read as "no aggression" on the way back
        TradeFlow flow = event.flow();
        stmt.setBigDecimal(10, flow.hasTakerSplit() ? flow.takerBuyVolume() : null);
        stmt.setBigDecimal(11, flow.hasSizeSplit() ? flow.largeBuyVolume() : null);
        stmt.setBigDecimal(12, flow.hasSizeSplit() ? flow.largeSellVolume() : null);
        stmt.setString(13, flow.hasTakerSplit() ? flow.source().name() : null);
    }

    private static void bindTick(PreparedStatement stmt, NormalizedPriceEvent event) throws SQLException {
        stmt.setString(1, event.instrument());
        stmt.setLong(2, event.eventTimestamp().toEpochMilli());
        stmt.setBigDecimal(3, event.price());
        stmt.setBigDecimal(4, event.quantity());
        stmt.setString(5, event.aggressorSide() == AggressorSide.UNKNOWN ? null : event.aggressorSide().name());
    }
}
