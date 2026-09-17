package dev.romeo.btctradingengine.persistence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #85: the ticks retention job issues bounded deletes below the cutoff until nothing is left. */
class TickRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    /** Records every prepared statement and answers executeUpdate from a scripted list of row counts. */
    private static final class FakeDatabase {
        final Deque<Integer> updateCounts = new ArrayDeque<>();
        final List<String> sql = new ArrayList<>();
        final List<Map<Integer, Object>> parameters = new ArrayList<>();
        int closedConnections;

        FakeDatabase(Integer... counts) {
            updateCounts.addAll(List.of(counts));
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> statement((String) args[0]);
                        case "close" -> {
                            closedConnections++;
                            yield null;
                        }
                        default -> null;
                    });
        }

        private PreparedStatement statement(String statementSql) {
            sql.add(statementSql);
            Map<Integer, Object> params = new TreeMap<>();
            parameters.add(params);
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setLong", "setInt" -> {
                            params.put((Integer) args[0], args[1]);
                            yield null;
                        }
                        case "executeUpdate" -> updateCounts.isEmpty() ? 0 : updateCounts.poll();
                        default -> null;
                    });
        }
    }

    private static TickRetentionJob job(FakeDatabase db, int days, int batchSize) {
        return new TickRetentionJob(db::connection, Clock.fixed(NOW, ZoneOffset.UTC), days, batchSize,
                Duration.ofMinutes(60), 0);
    }

    @Test
    void deletesInBoundedBatchesBelowTheCutoffUntilABatchComesBackShort() throws Exception {
        FakeDatabase db = new FakeDatabase(1000, 1000, 250);

        long deleted = job(db, 7, 1000).purgeOnce();

        assertEquals(2250, deleted);
        assertEquals(3, db.sql.size());
        long cutoff = NOW.minus(Duration.ofDays(7)).toEpochMilli();
        for (int i = 0; i < 3; i++) {
            assertEquals(TickRetentionJob.DELETE_SQL, db.sql.get(i));
            assertEquals(Map.of(1, cutoff, 2, 1000), db.parameters.get(i));
        }
        assertEquals(3, db.closedConnections);
    }

    @Test
    void deletedTotalAccumulatesAcrossRuns() throws Exception {
        TickRetentionJob job = job(new FakeDatabase(1000, 250, 10), 7, 1000);

        job.purgeOnce();
        job.purgeOnce();

        assertEquals(1260, job.deletedTotal(), "exported as ticks.retention.deleted (issue #104)");
    }

    @Test
    void deleteIsBoundedAndUsesTheIndexedTimeColumn() {
        String sql = TickRetentionJob.DELETE_SQL;
        assertTrue(sql.startsWith("DELETE FROM ticks"));
        assertTrue(sql.contains("time_ms < ?"));
        assertTrue(sql.contains("LIMIT ?"));
    }

    @Test
    void zeroDaysKeepsTicksForever() throws Exception {
        FakeDatabase db = new FakeDatabase(1000);
        TickRetentionJob job = job(db, 0, 1000);

        assertFalse(job.isEnabled());
        assertEquals(0, job.purgeOnce());
        job.start();
        job.close();
        assertTrue(db.sql.isEmpty());
    }

    @Test
    void nothingExpiredRunsASingleDelete() throws Exception {
        FakeDatabase db = new FakeDatabase(0);

        assertEquals(0, job(db, 7, 500).purgeOnce());
        assertEquals(1, db.sql.size());
    }

    @Test
    void cutoffIsNowMinusRetentionDays() {
        assertEquals(Instant.parse("2026-09-09T12:00:00Z").toEpochMilli(), TickRetentionJob.cutoffMillis(NOW, 7));
    }
}
