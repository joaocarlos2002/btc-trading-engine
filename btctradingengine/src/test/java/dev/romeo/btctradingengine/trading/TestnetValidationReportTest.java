package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestnetValidationReportTest {

    private Position closedPosition(BigDecimal entry, BigDecimal exit, BigDecimal quantity,
                                     BigDecimal targetQuantity, String exitReason) {
        Instant entryTime = Instant.parse("2026-01-01T00:00:00Z");
        Instant exitTime = entryTime.plusSeconds(600);
        Position pos = new Position("POS_1", Signal.BUY, entry, entryTime,
                BigDecimal.valueOf(2.0), BigDecimal.valueOf(1.5));
        pos.setTargetQuantity(targetQuantity);
        pos.applyFill(quantity);
        pos.restoreClosed(exit, exitTime, exitReason);
        return pos;
    }

    @Test
    public void aggregatesWinLossAndPnl() {
        Position winner = closedPosition(BigDecimal.valueOf(100), BigDecimal.valueOf(110),
                BigDecimal.valueOf(2), BigDecimal.valueOf(2), "TARGET_HIT");
        Position loser = closedPosition(BigDecimal.valueOf(100), BigDecimal.valueOf(95),
                BigDecimal.valueOf(2), BigDecimal.valueOf(2), "STOP_LOSS");

        TestnetValidationReport report = new TestnetValidationReport(List.of(winner, loser));

        assertEquals(2, report.getTotalTrades());
        assertEquals(1, report.getWinTrades());
        assertEquals(1, report.getLoseTrades());
        assertEquals(0, BigDecimal.valueOf(50).compareTo(report.getWinRate()));
        assertEquals(0, BigDecimal.valueOf(5).compareTo(report.getTotalPnL()));
        assertEquals(0, BigDecimal.valueOf(10).compareTo(report.getTotalNotionalPnL()));
        assertTrue(report.allFillsConfirmed());
        assertFalse(report.meetsMinimumTradeCount(100));
    }

    @Test
    public void flagsIncompleteFills() {
        Position partial = closedPosition(BigDecimal.valueOf(100), BigDecimal.valueOf(105),
                BigDecimal.valueOf(1), BigDecimal.valueOf(3), "MANUAL_CLOSE");

        TestnetValidationReport report = new TestnetValidationReport(List.of(partial));

        assertEquals(1, report.getIncompleteFillCount());
        assertFalse(report.allFillsConfirmed());
    }
}
