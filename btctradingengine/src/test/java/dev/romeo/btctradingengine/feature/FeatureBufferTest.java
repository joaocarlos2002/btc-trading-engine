package dev.romeo.btctradingengine.feature;

import dev.romeo.btctradingengine.model.CandleEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FeatureBufferTest {

    @Test
    void keepsAsManyCandlesAsItsCapacity() {
        FeatureBuffer buffer = new FeatureBuffer(300);
        for (int i = 0; i < 400; i++) {
            buffer.add(candle(100 + i, 1));
        }
        assertEquals(300, buffer.size());
        assertTrue(buffer.volatility(300).isPresent());
        assertTrue(buffer.averageVolume(300).isPresent());
    }

    @Test
    void volatilityIsThePopulationStdDevOfTheReturnsInPercent() {
        FeatureBuffer buffer = new FeatureBuffer(3);
        buffer.add(candle(100, 1));
        buffer.add(candle(110, 1)); // +10%
        buffer.add(candle(99, 1));  // -10%

        // returns +0.1 and -0.1: mean 0, std dev 0.1 -> 10%
        assertEquals(0, new BigDecimal("10").compareTo(buffer.volatility(3).orElseThrow().setScale(6, java.math.RoundingMode.HALF_UP)));
        // only the last return (-10%) with two closes: std dev of a single value is 0
        assertEquals(0, buffer.volatility(2).orElseThrow().signum());
    }

    @Test
    void volatilityOnlyUsesTheReturnsInsideTheWindowAfterEviction() {
        FeatureBuffer buffer = new FeatureBuffer(3);
        buffer.add(candle(50, 1));  // evicted, its +100% return into 100 must not count
        buffer.add(candle(100, 1));
        buffer.add(candle(110, 1));
        buffer.add(candle(99, 1));

        assertEquals(0, new BigDecimal("10").compareTo(buffer.volatility(3).orElseThrow().setScale(6, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void averageVolumeUsesTheLastPeriodsCandles() {
        FeatureBuffer buffer = new FeatureBuffer(3);
        buffer.add(candle(100, 10));
        buffer.add(candle(100, 2));
        buffer.add(candle(100, 4));

        assertEquals(0, new BigDecimal("3").compareTo(buffer.averageVolume(2).orElseThrow()));
        assertTrue(new FeatureBuffer(3).averageVolume(2).isEmpty());
    }

    @Test
    void rejectsWindowsLargerThanTheCapacity() {
        FeatureBuffer buffer = new FeatureBuffer(100);
        assertThrows(IllegalArgumentException.class, () -> buffer.volatility(300));
        assertThrows(IllegalArgumentException.class, () -> buffer.averageVolume(300));
        assertThrows(IllegalArgumentException.class, () -> new FeatureBuffer(0));
    }

    private static CandleEvent candle(double close, double volume) {
        BigDecimal price = BigDecimal.valueOf(close);
        Instant now = Instant.parse("2026-09-08T10:00:00Z");
        return new CandleEvent("BTCUSDT", now, now.plusSeconds(60), price, price, price, price,
                BigDecimal.valueOf(volume), 1);
    }
}
