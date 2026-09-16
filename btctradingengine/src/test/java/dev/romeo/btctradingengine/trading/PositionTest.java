package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PositionTest {

    private Position newPosition() {
        return new Position(
                "POS_1",
                Signal.BUY,
                BigDecimal.valueOf(100),
                Instant.now(),
                BigDecimal.valueOf(2.0),
                BigDecimal.valueOf(1.5)
        );
    }

    @Test
    public void partialFillTracksCumulativeQuantityAndRemaining() {
        Position pos = newPosition();
        pos.setTargetQuantity(BigDecimal.valueOf(10));

        pos.applyFill(BigDecimal.valueOf(3));
        assertEquals(BigDecimal.valueOf(3), pos.getQuantity());
        assertEquals(BigDecimal.valueOf(7), pos.getRemainingQuantity());
        assertFalse(pos.isFullyFilled());

        pos.applyFill(BigDecimal.valueOf(8));
        assertEquals(BigDecimal.valueOf(8), pos.getQuantity());
        assertEquals(BigDecimal.valueOf(2), pos.getRemainingQuantity());
        assertFalse(pos.isFullyFilled());

        pos.applyFill(BigDecimal.TEN);
        assertEquals(BigDecimal.TEN, pos.getQuantity());
        assertEquals(BigDecimal.ZERO, pos.getRemainingQuantity());
        assertTrue(pos.isFullyFilled());
    }

    @Test
    public void applyFillNeverReducesTheKnownQuantity() {
        Position pos = newPosition();
        pos.applyFill(BigDecimal.valueOf(5));

        pos.applyFill(BigDecimal.ZERO);
        pos.applyFill(BigDecimal.valueOf(3));

        assertEquals(BigDecimal.valueOf(5), pos.getQuantity());
    }

    @Test
    public void openSellLosingShowsNegativePnL() {
        Position pos = new Position("POS_2", Signal.SELL, BigDecimal.valueOf(100), Instant.now(),
                BigDecimal.valueOf(2.0), BigDecimal.valueOf(1.5));
        pos.updatePrice(BigDecimal.valueOf(101), Instant.now());

        assertEquals(0, pos.getPnL().compareTo(BigDecimal.valueOf(-1)));
        assertTrue(pos.getPnLPercent().signum() < 0);
    }

    @Test
    public void openSellWinningShowsPositivePnL() {
        Position pos = new Position("POS_3", Signal.SELL, BigDecimal.valueOf(100), Instant.now(),
                BigDecimal.valueOf(2.0), BigDecimal.valueOf(1.5));
        pos.updatePrice(BigDecimal.valueOf(98), Instant.now());

        assertEquals(0, pos.getPnL().compareTo(BigDecimal.valueOf(2)));
    }

    @Test
    public void openBuyPnLIsCurrentMinusEntry() {
        Position pos = newPosition();
        pos.updatePrice(BigDecimal.valueOf(103), Instant.now());
        assertEquals(0, pos.getPnL().compareTo(BigDecimal.valueOf(3)));

        pos.updatePrice(BigDecimal.valueOf(99), Instant.now());
        assertEquals(0, pos.getPnL().compareTo(BigDecimal.valueOf(-1)));
    }

    @Test
    public void remainingQuantityIsZeroWhenNoTargetSet() {
        Position pos = newPosition();
        assertEquals(BigDecimal.ZERO, pos.getRemainingQuantity());
        assertFalse(pos.isFullyFilled());
    }
}
