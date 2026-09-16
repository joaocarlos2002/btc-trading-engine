package dev.romeo.btctradingengine.dashboard;

import dev.romeo.btctradingengine.prediction.Signal;
import dev.romeo.btctradingengine.trading.Position;
import dev.romeo.btctradingengine.trading.PositionManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Issue #84: drawdown is measured on equity compounded from the initial capital, not on cumulative P&L. */
public class DashboardStateStatsTest {

    private static final BigDecimal CAPITAL = new BigDecimal("1000");

    private static Position closedBuy(int id, String entry, String exit) {
        Position position = new Position("POS_" + id, Signal.BUY, new BigDecimal(entry), Instant.now(),
                new BigDecimal("2.0"), new BigDecimal("1.5"));
        position.closeManual(new BigDecimal(exit), Instant.now());
        return position;
    }

    private static DashboardState.Stats statsFor(List<Position> positions) {
        PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
        manager.restoreClosedPositions(positions);
        try (DashboardState state = new DashboardState()) {
            state.attachPositionManager(manager);
            return state.stats(CAPITAL);
        }
    }

    @Test
    public void onlyLossesReportDrawdown() {
        // -10% then -10%: equity 1000 -> 900 -> 810, drawdown 19% from the initial capital.
        DashboardState.Stats stats = statsFor(List.of(
                closedBuy(1, "100", "90"),
                closedBuy(2, "100", "90")));

        assertEquals(0, stats.maxDrawdown().compareTo(new BigDecimal("19")), stats.maxDrawdown().toString());
    }

    @Test
    public void drawdownIsMeasuredFromThePeak() {
        // +20% then -25%: equity 1000 -> 1200 -> 900, drawdown 25% from the 1200 peak.
        DashboardState.Stats stats = statsFor(List.of(
                closedBuy(1, "100", "120"),
                closedBuy(2, "100", "75")));

        assertEquals(0, stats.maxDrawdown().compareTo(new BigDecimal("25")), stats.maxDrawdown().toString());
    }

    @Test
    public void onlyGainsReportNoDrawdown() {
        DashboardState.Stats stats = statsFor(List.of(
                closedBuy(1, "100", "110"),
                closedBuy(2, "100", "105")));

        assertEquals(0, stats.maxDrawdown().signum());
    }
}
