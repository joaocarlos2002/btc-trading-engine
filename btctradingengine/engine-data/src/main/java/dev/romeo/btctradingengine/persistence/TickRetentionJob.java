package dev.romeo.btctradingengine.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deletes ticks older than {@code db.ticks.retention.days} (issue #85): Mainnet BTCUSDT records 1-3 million
 * ticks a day and nothing else ever removes them. Rows go in bounded batches, each its own short transaction,
 * so the table is never locked for long and the live DatabaseWriter keeps inserting in between.
 *
 * <p>Retention also bounds the deterministic replay: {@link DatabaseTickReader} can only replay ticks newer
 * than the retention window. Set the property to 0 to keep ticks forever.
 */
public class TickRetentionJob implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(TickRetentionJob.class);

    /** The subquery walks idx_ticks_time (time_ms) and caps each delete at {@code LIMIT} rows. */
    static final String DELETE_SQL =
            "DELETE FROM ticks WHERE id IN (SELECT id FROM ticks WHERE time_ms < ? LIMIT ?)";

    @FunctionalInterface
    interface ConnectionSource {
        Connection get() throws SQLException;
    }

    private final ConnectionSource connections;
    private final Clock clock;
    private final int retentionDays;
    private final int batchSize;
    private final Duration interval;
    private final long pauseBetweenBatchesMs;
    private ScheduledExecutorService scheduler;
    private final AtomicLong deletedTotal = new AtomicLong();

    TickRetentionJob(ConnectionSource connections, Clock clock, int retentionDays, int batchSize,
                     Duration interval, long pauseBetweenBatchesMs) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        this.connections = connections;
        this.clock = clock;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.interval = interval;
        this.pauseBetweenBatchesMs = Math.max(0, pauseBetweenBatchesMs);
    }

    /** db.ticks.retention.days / batch.size / interval.minutes; 0 days keeps ticks forever. */
    public static TickRetentionJob create(DataSource dataSource, int retentionDays, int batchSize, Duration interval) {
        return new TickRetentionJob(dataSource::getConnection, Clock.systemUTC(), retentionDays, batchSize, interval, 50);
    }

    /** Ticks deleted since startup, across every run; read by the metrics (issue #104). */
    public long deletedTotal() {
        return deletedTotal.get();
    }

    public boolean isEnabled() {
        return retentionDays > 0;
    }

    /** Ticks with {@code time_ms} strictly below this are deleted. */
    static long cutoffMillis(Instant now, int retentionDays) {
        return now.minus(Duration.ofDays(retentionDays)).toEpochMilli();
    }

    /** Schedules the purge; the first run is a minute after startup so it does not compete with warmup. */
    public synchronized void start() {
        if (!isEnabled()) {
            logger.info("Tick retention disabled (db.ticks.retention.days=0): ticks are kept forever");
            return;
        }
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "TickRetention");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::runSafely, 1, interval.toMinutes(), TimeUnit.MINUTES);
        logger.info("Tick retention: keeping {} days of ticks, purging every {} min in batches of {}",
                retentionDays, interval.toMinutes(), batchSize);
    }

    private void runSafely() {
        try {
            purgeOnce();
        } catch (Exception e) {
            logger.warn("Tick retention run failed; retrying next interval: {}", e.getMessage());
        }
    }

    /**
     * Deletes expired ticks batch by batch until a batch comes back smaller than {@code batchSize}.
     *
     * @return rows deleted in this run
     */
    public long purgeOnce() throws SQLException {
        if (!isEnabled()) {
            return 0;
        }
        long cutoff = cutoffMillis(clock.instant(), retentionDays);
        long total = 0;
        while (!Thread.currentThread().isInterrupted()) {
            int deleted;
            try (Connection connection = connections.get();
                 PreparedStatement statement = connection.prepareStatement(DELETE_SQL)) {
                statement.setLong(1, cutoff);
                statement.setInt(2, batchSize);
                deleted = statement.executeUpdate();
            }
            total += deleted;
            deletedTotal.addAndGet(deleted);
            if (deleted < batchSize) {
                break;
            }
            if (pauseBetweenBatchesMs > 0) {
                try {
                    // Deliberate pause between DELETE batches so the table is not held busy
                    //noinspection BusyWait
                    Thread.sleep(pauseBetweenBatchesMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (total > 0) {
            logger.info("Tick retention deleted {} ticks older than {}", total, Instant.ofEpochMilli(cutoff));
        }
        return total;
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
