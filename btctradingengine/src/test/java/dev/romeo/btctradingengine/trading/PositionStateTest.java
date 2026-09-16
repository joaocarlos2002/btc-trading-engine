package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #111: the position lifecycle only moves through validated transitions. */
public class PositionStateTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    private static Position position(PositionState state) {
        return new Position("POS_1", Signal.BUY, new BigDecimal("100"), NOW,
                new BigDecimal("2.0"), new BigDecimal("1.5"), state);
    }

    @Test
    public void allowsTheDocumentedTransitions() {
        assertTrue(PositionState.PENDING_ENTRY.canTransitionTo(PositionState.OPEN));
        assertTrue(PositionState.PENDING_ENTRY.canTransitionTo(PositionState.FAILED));
        assertTrue(PositionState.OPEN.canTransitionTo(PositionState.EXIT_PENDING));
        assertTrue(PositionState.OPEN.canTransitionTo(PositionState.CLOSED));
        assertTrue(PositionState.EXIT_PENDING.canTransitionTo(PositionState.CLOSED));
        assertTrue(PositionState.EXIT_PENDING.canTransitionTo(PositionState.OPEN));
    }

    @Test
    public void rejectsIllegalTransitions() {
        assertFalse(PositionState.PENDING_ENTRY.canTransitionTo(PositionState.EXIT_PENDING));
        assertFalse(PositionState.PENDING_ENTRY.canTransitionTo(PositionState.CLOSED));
        assertFalse(PositionState.EXIT_PENDING.canTransitionTo(PositionState.EXIT_PENDING));
        assertFalse(PositionState.EXIT_PENDING.canTransitionTo(PositionState.FAILED));
        for (PositionState terminal : List.of(PositionState.CLOSED, PositionState.FAILED)) {
            for (PositionState target : PositionState.values()) {
                assertFalse(terminal.canTransitionTo(target), terminal + " -> " + target);
            }
        }
    }

    @Test
    public void illegalTransitionThrowsAndKeepsTheState() {
        Position pos = position(PositionState.PENDING_ENTRY);

        assertThrows(PositionState.IllegalTransitionException.class,
                () -> pos.close(new BigDecimal("101"), NOW, ExitReason.TARGET_HIT));
        assertEquals(PositionState.PENDING_ENTRY, pos.getState());
        assertNull(pos.getExitPrice());
    }

    @Test
    public void closingTwiceThrows() {
        Position pos = position(PositionState.OPEN);
        pos.close(new BigDecimal("101"), NOW, ExitReason.MANUAL_CLOSE);

        assertEquals(PositionState.CLOSED, pos.getState());
        assertThrows(PositionState.IllegalTransitionException.class,
                () -> pos.close(new BigDecimal("102"), NOW, ExitReason.MANUAL_CLOSE));
    }

    @Test
    public void entryFailureReasonsEndFailed() {
        for (ExitReason reason : List.of(ExitReason.ORDER_FAILED, ExitReason.VALIDATION_FAILED, ExitReason.ERROR)) {
            Position pos = position(PositionState.PENDING_ENTRY);
            pos.close(new BigDecimal("100"), NOW, reason);
            assertEquals(PositionState.FAILED, pos.getState(), reason.name());
            assertFalse(pos.isOpen());
        }
        Position restored = position(PositionState.OPEN);
        restored.restoreClosed(new BigDecimal("100"), NOW, "STOP_LOSS");
        assertEquals(PositionState.CLOSED, restored.getState());
    }

    @Test
    public void parsesStoredNames() {
        assertEquals(PositionState.EXIT_PENDING, PositionState.parse("exit_pending"));
        assertNull(PositionState.parse(null));
        assertNull(PositionState.parse("SOMETHING"));
    }

    // ---- through the PositionManager ----

    private static final class Exchange extends BinanceOrderExecutor {
        boolean buyFails;
        boolean sellFails;
        final List<PositionState> statesDuringSell = new ArrayList<>();
        PositionManager manager;

        Exchange() {
            super("test-key", "test-secret");
        }

        @Override
        public OrderResult executeBuyMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            return buyFails
                    ? new OrderResult(false, null, BigDecimal.ZERO, BigDecimal.ZERO, "rejected")
                    : new OrderResult(true, "1", new BigDecimal("0.5"), new BigDecimal("100"), null);
        }

        @Override
        public OrderResult executeSellMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            manager.getOpenPosition().ifPresent(p -> statesDuringSell.add(p.getState()));
            return sellFails
                    ? new OrderResult(false, null, BigDecimal.ZERO, BigDecimal.ZERO, "rejected")
                    : new OrderResult(true, "2", quantity, new BigDecimal("99"), null);
        }

        @Override
        public Optional<QueriedOrder> queryOrder(String symbol, String clientOrderId) {
            return Optional.empty();
        }

        @Override
        public SymbolFilters getSymbolFilters(String symbol) {
            return new SymbolFilters(symbol, new BigDecimal("10"), new BigDecimal("0.00001"),
                    new BigDecimal("1000"), new BigDecimal("0.00001"));
        }

        @Override
        public BalanceResult getBalance(String asset) {
            return new BalanceResult(true, new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"), null);
        }
    }

    private final Exchange exchange = new Exchange();
    private final List<PositionState> persisted = new ArrayList<>();

    private PositionManager realManager() {
        PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"),
                pos -> persisted.add(pos.getState()));
        manager.setRealTradingMode(exchange, new PortfolioManager(new BigDecimal("1000"), new BigDecimal("50")), "BTCUSDT");
        manager.markReconciliationComplete();
        exchange.manager = manager;
        return manager;
    }

    @Test
    public void realEntryIsPersistedPendingThenOpen() {
        PositionManager manager = realManager();

        assertTrue(manager.openManualBuy(new BigDecimal("100"), NOW).opened());

        assertEquals(PositionState.OPEN, manager.getOpenPosition().orElseThrow().getState());
        assertEquals(PositionState.PENDING_ENTRY, persisted.getFirst());
        assertEquals(PositionState.OPEN, persisted.getLast());
    }

    @Test
    public void failedRealEntryEndsFailed() {
        exchange.buyFails = true;
        PositionManager manager = realManager();

        assertFalse(manager.openManualBuy(new BigDecimal("100"), NOW).opened());

        assertEquals(PositionState.FAILED, manager.getClosedPositions().getLast().getState());
        assertEquals(PositionState.FAILED, persisted.getLast());
    }

    @Test
    public void exitRunsInExitPendingAndEndsClosed() {
        PositionManager manager = realManager();
        manager.openManualBuy(new BigDecimal("100"), NOW);

        assertTrue(manager.closeManualPosition(new BigDecimal("99"), NOW));

        assertEquals(List.of(PositionState.EXIT_PENDING), exchange.statesDuringSell);
        assertEquals(PositionState.CLOSED, manager.getClosedPositions().getLast().getState());
    }

    @Test
    public void failedExitReturnsToOpen() {
        exchange.sellFails = true;
        PositionManager manager = realManager();
        manager.openManualBuy(new BigDecimal("100"), NOW);

        assertFalse(manager.closeManualPosition(new BigDecimal("99"), NOW));

        assertEquals(PositionState.OPEN, manager.getOpenPosition().orElseThrow().getState());
        assertTrue(persisted.contains(PositionState.EXIT_PENDING));
        assertEquals(PositionState.OPEN, persisted.getLast());
    }

    @Test
    public void simulatedEntryOpensDirectly() {
        PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"),
                pos -> persisted.add(pos.getState()));

        assertTrue(manager.openManualBuy(new BigDecimal("100"), NOW).opened());
        assertTrue(manager.closeManualPosition(new BigDecimal("101"), NOW));

        assertEquals(List.of(PositionState.OPEN, PositionState.CLOSED), persisted);
    }
}
