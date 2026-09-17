package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.persistence.PostgresTestDatabase;
import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TradeJournal's upsert, restore queries and execution log on real PostgreSQL (issue #103). */
@Testcontainers(disabledWithoutDocker = true)
class TradeJournalIT {
    private static final BigDecimal TARGET = new BigDecimal("1.5");
    private static final BigDecimal STOP = new BigDecimal("0.5");
    private static final Instant ENTRY = Instant.parse("2026-09-16T10:00:00Z");

    private TradeJournal journal;

    @BeforeEach
    void emptyTables() throws SQLException {
        PostgresTestDatabase.truncateAll();
        journal = new TradeJournal(PostgresTestDatabase.dataSource());
    }

    @Test
    void pendingEntryIsUpdatedInPlaceAndRestoredAsOpenWithItsQuantity() throws SQLException {
        Position position = new Position("POS_7", Signal.BUY, new BigDecimal("60000"), ENTRY, TARGET, STOP,
                PositionState.PENDING_ENTRY);
        journal.recordTrade(position, "BTCUSDT");
        assertEquals("PENDING_ENTRY", PostgresTestDatabase.queryValue("SELECT state FROM trades WHERE position_id = 'POS_7'"));

        position.applyFill(new BigDecimal("0.00833"));
        position.setEntryPrice(new BigDecimal("60010.12345678"));
        position.transitionTo(PositionState.OPEN);
        position.transitionTo(PositionState.EXIT_PENDING);
        journal.recordTrade(position, "BTCUSDT");

        assertEquals(1, PostgresTestDatabase.count("SELECT count(*) FROM trades"));
        Position restored = journal.loadOpenPosition("BTCUSDT", TARGET, STOP).orElseThrow();
        assertEquals("POS_7", restored.getPositionId());
        assertEquals(PositionState.OPEN, restored.getState(), "an order in flight is restored as OPEN for reconciliation");
        assertEquals(0, new BigDecimal("0.00833").compareTo(restored.getQuantity()));
        assertEquals(0, new BigDecimal("60010.12345678").compareTo(restored.getEntryPrice()));
        assertEquals(ENTRY, restored.getEntryTime());
        assertTrue(journal.loadOpenPosition("ETHUSDT", TARGET, STOP).isEmpty());
    }

    @Test
    void closedTradeIsNoLongerOpenAndLoadsWithItsExit() throws SQLException {
        Position position = new Position("POS_1", Signal.BUY, new BigDecimal("60000"), ENTRY, TARGET, STOP);
        position.setQuantity(new BigDecimal("0.01"));
        journal.recordTrade(position, "BTCUSDT");
        position.close(new BigDecimal("59700"), ENTRY.plusSeconds(600), ExitReason.STOP_LOSS);
        journal.recordTrade(position, "BTCUSDT");

        assertEquals(Optional.empty(), journal.loadOpenPosition("BTCUSDT", TARGET, STOP));
        assertEquals("CLOSED", PostgresTestDatabase.queryValue("SELECT state FROM trades"));
        // pnl is per unit (USDT per BTC), not multiplied by the quantity
        assertEquals(0, new BigDecimal("-300").compareTo((BigDecimal) PostgresTestDatabase.queryValue("SELECT pnl FROM trades")));

        List<Position> closed = journal.loadClosedPositions("BTCUSDT", TARGET, STOP, 10);
        assertEquals(1, closed.size());
        assertEquals(ExitReason.STOP_LOSS, closed.getFirst().getExitReason());
        assertEquals(0, new BigDecimal("59700").compareTo(closed.getFirst().getExitPrice()));
    }

    @Test
    void mostRecentOpenPositionWinsAndClosedAreOldestFirst() {
        for (int i = 1; i <= 3; i++) {
            Position position = new Position("POS_" + i, Signal.BUY, new BigDecimal("60000"), ENTRY.plusSeconds(i * 60L), TARGET, STOP);
            if (i < 3) {
                position.close(new BigDecimal("60100"), ENTRY.plusSeconds(i * 60L + 30), ExitReason.TARGET_HIT);
            }
            journal.recordTrade(position, "BTCUSDT");
        }

        assertEquals("POS_3", journal.loadOpenPosition("BTCUSDT", TARGET, STOP).orElseThrow().getPositionId());
        assertEquals(List.of("POS_1", "POS_2"), journal.loadClosedPositions("BTCUSDT", TARGET, STOP, 10).stream()
                .map(Position::getPositionId).toList());
    }

    @Test
    void executionLogRowIsWritten() throws SQLException {
        journal.recordExecutionLog(new PositionManager.ExecutionEvent("POS_1", "ENTRY", Signal.BUY,
                new BigDecimal("60000"), ENTRY, 0.73), "BTCUSDT");

        assertEquals(1, PostgresTestDatabase.count("SELECT count(*) FROM execution_log WHERE action = 'ENTRY' AND signal = 'BUY'"));
    }
}
