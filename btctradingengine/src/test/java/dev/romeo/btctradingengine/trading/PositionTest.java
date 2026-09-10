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
    public void remainingQuantityIsZeroWhenNoTargetSet() {
        Position pos = newPosition();
        assertEquals(BigDecimal.ZERO, pos.getRemainingQuantity());
        assertFalse(pos.isFullyFilled());
    }
}
