package dev.romeo.btctradingengine.dashboard;

import dev.romeo.btctradingengine.prediction.Signal;
import dev.romeo.btctradingengine.trading.Position;
import dev.romeo.btctradingengine.trading.PositionManager;
import dev.romeo.btctradingengine.trading.PositionState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Issue #85: "trades" is republished only when positions change, capped at the last 50 closed. */
class DashboardStatePositionsTest {

    private static Position position(int id) {
        return new Position("POS_" + id, Signal.BUY, new BigDecimal("100"), Instant.now(),
                new BigDecimal("2.0"), new BigDecimal("1.5"));
    }

    private static Position closed(int id) {
        Position position = position(id);
        position.closeManual(new BigDecimal("101"), Instant.now());
        return position;
    }

    /** Counts full closed-list copies so the test can prove per-tick refreshes do not make them. */
    private static final class FakeManager extends PositionManager {
        final List<Position> closed = new ArrayList<>();
        Position open;
        int fullCopies;
        int recentCopies;

        FakeManager() {
            super(new BigDecimal("2.0"), new BigDecimal("1.5"));
        }

        @Override
        public synchronized Optional<Position> getOpenPosition() {
            return Optional.ofNullable(open);
        }

        @Override
        public synchronized List<Position> getClosedPositions() {
            fullCopies++;
            return new ArrayList<>(closed);
        }

        @Override
        public synchronized int getClosedPositionCount() {
            return closed.size();
        }

        @Override
        public synchronized List<Position> getRecentClosedPositions(int limit) {
            recentCopies++;
            return new ArrayList<>(closed.subList(Math.max(0, closed.size() - limit), closed.size()));
        }
    }

    @Test
    void tradesArePublishedOnlyWhenPositionsChange() {
        FakeManager manager = new FakeManager();
        manager.closed.add(closed(1));
        try (DashboardState state = new DashboardState(new BigDecimal("100"))) {
            state.attachPositionManager(manager);

            state.refreshPositions();
            assertNotNull(state.pendingPayload("trades"));
            state.clearPendingEvents();

            // Ticks with nothing changed and no open position: nothing to publish
            for (int i = 0; i < 100; i++) {
                state.refreshPositions();
            }
            assertNull(state.pendingPayload("trades"));
            assertNull(state.pendingPayload("position"));
            assertEquals(1, manager.recentCopies);
            assertEquals(0, manager.fullCopies);

            // Opening a position is a change
            manager.open = position(2);
            state.refreshPositions();
            DashboardState.TradesPayload trades = (DashboardState.TradesPayload) state.pendingPayload("trades");
            assertSame(manager.open, trades.open());
            state.clearPendingEvents();

            // Live ticks on the open position only queue the light "position" event
            state.refreshPositions();
            assertNull(state.pendingPayload("trades"));
            assertSame(manager.open, state.pendingPayload("position"));
            state.clearPendingEvents();

            // A state change on the same position republishes trades
            manager.open.transitionTo(PositionState.EXIT_PENDING);
            state.refreshPositions();
            assertNotNull(state.pendingPayload("trades"));
            state.clearPendingEvents();

            // Closing it republishes trades without an open position
            Position closing = manager.open;
            closing.closeManual(new BigDecimal("101"), Instant.now());
            manager.closed.add(closing);
            manager.open = null;
            state.refreshPositions();
            trades = (DashboardState.TradesPayload) state.pendingPayload("trades");
            assertNull(trades.open());
            assertEquals(2, trades.closed().size());
            assertEquals(4, manager.recentCopies);
            assertEquals(0, manager.fullCopies);
        }
    }

    @Test
    void publishedClosedTradesAreCappedAtTheLast50() {
        FakeManager manager = new FakeManager();
        for (int i = 1; i <= 120; i++) {
            manager.closed.add(closed(i));
        }
        try (DashboardState state = new DashboardState(new BigDecimal("100"))) {
            state.attachPositionManager(manager);
            state.refreshPositions();

            DashboardState.TradesPayload trades = (DashboardState.TradesPayload) state.pendingPayload("trades");
            assertEquals(DashboardState.MAX_PUBLISHED_CLOSED, trades.closed().size());
            assertEquals("POS_71", trades.closed().getFirst().getPositionId());
            assertEquals("POS_120", trades.closed().getLast().getPositionId());
        }
    }

    @Test
    void recentClosedPositionsReturnsOnlyTheTail() {
        PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
        List<Position> positions = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            positions.add(closed(i));
        }
        manager.restoreClosedPositions(positions);

        assertEquals(5, manager.getClosedPositionCount());
        assertEquals(List.of("POS_4", "POS_5"),
                manager.getRecentClosedPositions(2).stream().map(Position::getPositionId).toList());
        assertEquals(5, manager.getRecentClosedPositions(50).size());
    }
}
