package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AdxIndicatorTest {

    @Test
    public void returnsEmptyDuringWarmup() {
        AdxIndicator adx = new AdxIndicator(2);

        // Needs 1 seed candle + 2 candles for the DM/TR averages + 1 more for the DX average.
        assertTrue(adx.update(candle("10", "8", "9")).isEmpty());
        assertTrue(adx.update(candle("11", "9", "10")).isEmpty());
        assertTrue(adx.update(candle("12", "10", "11")).isEmpty());
        assertTrue(adx.update(candle("13", "11", "12")).isPresent());
    }

    @Test
    public void perfectlyMonotonicUptrendGivesMaxAdxAndZeroMinusDi() {
        AdxIndicator adx = new AdxIndicator(2);

        adx.update(candle("10", "8", "9"));
        adx.update(candle("11", "9", "10"));
        adx.update(candle("12", "10", "11"));
        Optional<AdxIndicator.AdxValue> result = adx.update(candle("13", "11", "12"));

        AdxIndicator.AdxValue value = result.orElseThrow();
        // Every bar moves up by 1 with TR=2: +DM avg=1, -DM avg=0, TR avg=2
        // -> +DI=50, -DI=0, DX=100 on every bar -> ADX=100
        assertEquals(new BigDecimal("100.00"), value.adx().setScale(2, RoundingMode.HALF_EVEN));
        assertEquals(new BigDecimal("50.00"), value.plusDi().setScale(2, RoundingMode.HALF_EVEN));
        assertEquals(BigDecimal.ZERO, value.minusDi().stripTrailingZeros());
    }

    @Test
    public void perfectlyMonotonicDowntrendGivesMaxAdxAndZeroPlusDi() {
        AdxIndicator adx = new AdxIndicator(2);

        adx.update(candle("13", "11", "12"));
        adx.update(candle("12", "10", "11"));
        adx.update(candle("11", "9", "10"));
        Optional<AdxIndicator.AdxValue> result = adx.update(candle("10", "8", "9"));

        AdxIndicator.AdxValue value = result.orElseThrow();
        assertEquals(new BigDecimal("100.00"), value.adx().setScale(2, RoundingMode.HALF_EVEN));
        assertEquals(BigDecimal.ZERO, value.plusDi().stripTrailingZeros());
        assertEquals(new BigDecimal("50.00"), value.minusDi().setScale(2, RoundingMode.HALF_EVEN));
    }

    @Test
    public void calculatesKnownValueOnAlternatingSeries() {
        AdxIndicator adx = new AdxIndicator(2);

        adx.update(candle("10", "8", "9"));
        adx.update(candle("11", "9", "10"));   // +DM=1, -DM=0, TR=2
        adx.update(candle("10", "7", "8"));    // +DM=0, -DM=2, TR=3 -> DX=33.33333333
        Optional<AdxIndicator.AdxValue> result = adx.update(candle("12", "9", "11")); // +DM=2, -DM=0, TR=4 -> DX=0

        AdxIndicator.AdxValue value = result.orElseThrow();
        // ADX = avg(33.33333333, 0) = 16.666...
        assertEquals(new BigDecimal("16.67"), value.adx().setScale(2, RoundingMode.HALF_EVEN));
        // +DM avg == -DM avg == 1 over the last two bars, so both DIs match
        assertEquals(value.plusDi(), value.minusDi());
    }

    @Test
    public void flatCandlesGiveZeroAdxWithoutDividingByZero() {
        AdxIndicator adx = new AdxIndicator(2);

        adx.update(candle("100", "100", "100"));
        adx.update(candle("100", "100", "100"));
        adx.update(candle("100", "100", "100"));
        Optional<AdxIndicator.AdxValue> result = adx.update(candle("100", "100", "100"));

        AdxIndicator.AdxValue value = result.orElseThrow();
        assertEquals(0, value.adx().compareTo(BigDecimal.ZERO));
        assertEquals(0, value.plusDi().compareTo(BigDecimal.ZERO));
        assertEquals(0, value.minusDi().compareTo(BigDecimal.ZERO));
    }

    @Test
    public void highEqualToLowIsHandled() {
        AdxIndicator adx = new AdxIndicator(2);

        adx.update(candle("10", "10", "10"));
        adx.update(candle("11", "11", "11"));
        adx.update(candle("12", "12", "12"));
        Optional<AdxIndicator.AdxValue> result = adx.update(candle("13", "13", "13"));

        AdxIndicator.AdxValue value = result.orElseThrow();
        assertEquals(new BigDecimal("100.00"), value.adx().setScale(2, RoundingMode.HALF_EVEN));
    }

    private CandleEvent candle(String high, String low, String close) {
        Instant openTime = Instant.parse("2026-09-08T10:00:00Z");

        return new CandleEvent(
                "BTC/USD",
                openTime,
                openTime.plusSeconds(60),
                new BigDecimal(close),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(close),
                new BigDecimal("1000"),
                10
        );
    }
}
