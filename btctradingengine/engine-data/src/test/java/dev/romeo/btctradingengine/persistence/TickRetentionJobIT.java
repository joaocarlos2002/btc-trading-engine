package dev.romeo.btctradingengine.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TickRetentionJob's batched DELETE ... WHERE id IN (SELECT ... LIMIT ?) on real PostgreSQL (issue
 * #103): the statement had only run against a fake connection, so its syntax and batching were
 * never proven.
 */
@Testcontainers(disabledWithoutDocker = true)
class TickRetentionJobIT {
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @BeforeEach
    void emptyTables() throws SQLException {
        PostgresTestDatabase.truncateAll();
    }

    private static void insertTicks(Instant at, int count) throws SQLException {
        try (Connection connection = PostgresTestDatabase.dataSource().getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO ticks (symbol, time_ms, price, quantity) VALUES ('BTCUSDT', ?, 60000, 0.001)")) {
            for (int i = 0; i < count; i++) {
                statement.setLong(1, at.toEpochMilli() + i);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static TickRetentionJob job(int retentionDays, int batchSize) {
        return new TickRetentionJob(
                () -> PostgresTestDatabase.dataSource().getConnection(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                retentionDays,
                batchSize,
                Duration.ofMinutes(60),
                0);
    }

    @Test
    void deletesOnlyTicksOlderThanRetentionInSeveralBatches() throws Exception {
        Instant cutoff = NOW.minus(Duration.ofDays(7));
        insertTicks(cutoff.minus(Duration.ofDays(3)), 25); // expired
        insertTicks(cutoff.minusMillis(100), 10); // expired, last ms before the cutoff
        insertTicks(cutoff, 5); // exactly at the cutoff: kept
        insertTicks(NOW.minus(Duration.ofHours(1)), 12); // recent

        TickRetentionJob job = job(7, 10);
        long deleted = job.purgeOnce();

        assertEquals(35, deleted);
        assertEquals(35, job.deletedTotal());
        assertEquals(17, PostgresTestDatabase.count("SELECT count(*) FROM ticks"));
        assertEquals(
                0,
                PostgresTestDatabase.count(
                        "SELECT count(*) FROM ticks WHERE time_ms < ?", cutoff.toEpochMilli()));
        assertEquals(0, job.purgeOnce(), "a second run finds nothing left");
    }

    @Test
    void exactMultipleOfTheBatchSizeStopsAfterAnEmptyBatch() throws Exception {
        insertTicks(NOW.minus(Duration.ofDays(30)), 20);

        assertEquals(20, job(7, 10).purgeOnce());
        assertEquals(0, PostgresTestDatabase.count("SELECT count(*) FROM ticks"));
    }

    @Test
    void zeroDaysKeepsEverything() throws Exception {
        insertTicks(NOW.minus(Duration.ofDays(365)), 3);

        assertEquals(0, job(0, 10).purgeOnce());
        assertEquals(3, PostgresTestDatabase.count("SELECT count(*) FROM ticks"));
    }
}
