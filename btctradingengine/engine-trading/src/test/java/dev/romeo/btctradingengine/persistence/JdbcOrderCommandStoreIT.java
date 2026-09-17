package dev.romeo.btctradingengine.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.romeo.btctradingengine.trading.OrderCommand;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The order outbox on real PostgreSQL (issue #103): upsert on retry, status updates, JSON payload,
 * unresolved query.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcOrderCommandStoreIT {

    private JdbcOrderCommandStore store;

    @BeforeEach
    void emptyTables() throws SQLException {
        PostgresTestDatabase.truncateAll();
        store = new JdbcOrderCommandStore(PostgresTestDatabase::dataSource);
    }

    @Test
    void lifecycleFromPendingToConfirmed() {
        store.record(
                "btce-BTCUSDT-POS_1-entry",
                "POS_1",
                OrderCommand.Type.ENTRY,
                Map.of("symbol", "BTCUSDT", "side", "BUY", "quantity", "0.00833"));

        OrderCommand pending = store.find("btce-BTCUSDT-POS_1-entry").orElseThrow();
        assertEquals(OrderCommand.Status.PENDING, pending.status());
        assertEquals(1, pending.attempts());
        assertEquals(
                Map.of("symbol", "BTCUSDT", "side", "BUY", "quantity", "0.00833"),
                pending.payload());

        store.markSent("btce-BTCUSDT-POS_1-entry");
        assertEquals(
                OrderCommand.Status.SENT,
                store.find("btce-BTCUSDT-POS_1-entry").orElseThrow().status());
        assertEquals(1, store.findUnresolved().size());

        store.markConfirmed("btce-BTCUSDT-POS_1-entry");
        OrderCommand confirmed = store.find("btce-BTCUSDT-POS_1-entry").orElseThrow();
        assertEquals(OrderCommand.Status.CONFIRMED, confirmed.status());
        assertFalse(confirmed.updatedAt().isBefore(confirmed.createdAt()));
        assertTrue(store.findUnresolved().isEmpty());
    }

    @Test
    void recordingAgainResetsTheSameRowAndCountsTheAttempt() throws SQLException {
        store.record("exit-1", "POS_1", OrderCommand.Type.EXIT, Map.of("reason", "STOP_LOSS"));
        store.markSent("exit-1");
        store.markFailed("exit-1", "{\"code\":-2010,\"msg\":\"Account has insufficient balance\"}");
        assertEquals(
                "{\"code\":-2010,\"msg\":\"Account has insufficient balance\"}",
                store.find("exit-1").orElseThrow().lastError());

        store.record("exit-1", "POS_1", OrderCommand.Type.EXIT, Map.of("reason", "MANUAL_CLOSE"));

        OrderCommand retried = store.find("exit-1").orElseThrow();
        assertEquals(OrderCommand.Status.PENDING, retried.status());
        assertEquals(2, retried.attempts());
        assertNull(retried.lastError());
        assertEquals("MANUAL_CLOSE", retried.payload().get("reason"));
        assertEquals(1, PostgresTestDatabase.count("SELECT count(*) FROM order_commands"));
    }

    @Test
    void unresolvedAreOldestFirstAndSkipResolved() {
        store.record("a", "POS_1", OrderCommand.Type.ENTRY, Map.of());
        store.record("b", "POS_1", OrderCommand.Type.OCO, Map.of());
        store.record(OrderCommand.cancelKey("b"), "POS_1", OrderCommand.Type.CANCEL, null);
        store.record("c", "POS_2", OrderCommand.Type.EXIT, Map.of());
        store.markSent("b");
        store.markConfirmed("a");
        store.markFailed("c", "boom");

        List<OrderCommand> unresolved = store.findUnresolved();
        assertEquals(
                List.of("b", "cancel:b"),
                unresolved.stream().map(OrderCommand::clientOrderId).toList());
        assertEquals(Map.of(), unresolved.get(1).payload(), "a null payload is stored as {}");
    }

    @Test
    void unknownIdIsEmptyAndUpdatesOfUnknownIdsAreHarmless() {
        store.markConfirmed("never-recorded");
        assertTrue(store.find("never-recorded").isEmpty());
    }
}
