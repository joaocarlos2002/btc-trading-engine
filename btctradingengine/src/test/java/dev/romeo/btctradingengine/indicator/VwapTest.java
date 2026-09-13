package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VwapTest {

    @Test
    public void dailyAnchorHasNoWarmupAndWeightsByVolume() {
        Vwap vwap = new Vwap(VwapAnchor.DAILY, 300);

        // typical = (10+8+9)/3 = 9, volume 100
        Optional<BigDecimal> first = vwap.update(candle("10", "8", "9", "100", "2026-09-08T10:00:00Z"));
        assertEquals(0, first.orElseThrow().compareTo(new BigDecimal("9")));

        // typical = (12+10+11)/3 = 11, volume 100 -> (900 + 1100) / 200 = 10
        Optional<BigDecimal> second = vwap.update(candle("12", "10", "11", "100", "2026-09-08T10:01:00Z"));
        assertEquals(0, second.orElseThrow().compareTo(BigDecimal.TEN));
    }

    @Test
    public void dailyAnchorWeightsByVolumeNotEqually() {
        Vwap vwap = new Vwap(VwapAnchor.DAILY, 300);

        vwap.update(candle("10", "8", "9", "300", "2026-09-08T10:00:00Z"));   // typical 9, vol 300
        Optional<BigDecimal> result = vwap.update(candle("12", "10", "11", "100", "2026-09-08T10:01:00Z"));

        // (9*300 + 11*100) / 400 = 3800/400 = 9.5
        assertEquals(0, result.orElseThrow().compareTo(new BigDecimal("9.5")));
    }

    @Test
    public void dailyAnchorResetsOnUtcDayRollover() {
        Vwap vwap = new Vwap(VwapAnchor.DAILY, 300);

        vwap.update(candle("10", "8", "9", "100", "2026-09-08T23:58:00Z"));
        vwap.update(candle("10", "8", "9", "100", "2026-09-08T23:59:00Z"));

        // First candle of the next UTC day: accumulator restarts, so VWAP == this candle's typical
        Optional<BigDecimal> afterRollover = vwap.update(candle("21", "19", "20", "100", "2026-09-09T00:00:00Z"));
        assertEquals(0, afterRollover.orElseThrow().compareTo(new BigDecimal("20")));
    }

    @Test
    public void rollingAnchorStaysEmptyUntilWindowIsFull() {
        Vwap vwap = new Vwap(VwapAnchor.ROLLING, 3);

        assertTrue(vwap.update(candle("10", "8", "9", "100", "2026-09-08T10:00:00Z")).isEmpty());
        assertTrue(vwap.update(candle("10", "8", "9", "100", "2026-09-08T10:01:00Z")).isEmpty());
        assertTrue(vwap.update(candle("10", "8", "9", "100", "2026-09-08T10:02:00Z")).isPresent());
    }

    @Test
    public void rollingAnchorIgnoresTheUtcDayAndSlidesTheWindow() {
        Vwap vwap = new Vwap(VwapAnchor.ROLLING, 2);

        vwap.update(candle("10", "8", "9", "100", "2026-09-08T23:59:00Z"));    // typical 9
        vwap.update(candle("21", "19", "20", "100", "2026-09-09T00:00:00Z"));  // typical 20
        // Window holds both candles across the day boundary: (9 + 20)/2 = 14.5
        Optional<BigDecimal> spanning = vwap.update(candle("21", "19", "20", "100", "2026-09-09T00:01:00Z"));

        // After the third candle the first one expired: (20 + 20)/2 = 20
        assertEquals(0, spanning.orElseThrow().compareTo(new BigDecimal("20")));
    }

    @Test
    public void zeroVolumeFallsBackToTypicalPriceInsteadOfDividingByZero() {
        Vwap vwap = new Vwap(VwapAnchor.DAILY, 300);

        Optional<BigDecimal> result = vwap.update(candle("12", "10", "11", "0", "2026-09-08T10:00:00Z"));

        assertEquals(0, result.orElseThrow().compareTo(new BigDecimal("11")));
    }

    private CandleEvent candle(String high, String low, String close, String volume, String closeTime) {
        Instant closeInstant = Instant.parse(closeTime);

        return new CandleEvent(
                "BTC/USD",
                closeInstant.minusSeconds(60),
                closeInstant,
                new BigDecimal(close),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(close),
                new BigDecimal(volume),
                10
        );
    }
}
