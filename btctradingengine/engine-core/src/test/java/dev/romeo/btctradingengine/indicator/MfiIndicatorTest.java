package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MfiIndicatorTest {

    @Test
    public void returnsEmptyDuringWarmup() {
        MfiIndicator mfi = new MfiIndicator(2);

        // One seed candle to establish the previous typical price, then 2 flows
        assertTrue(mfi.update(candle("10", "10", "10", "100")).isEmpty());
        assertTrue(mfi.update(candle("12", "12", "12", "100")).isEmpty());
        assertTrue(mfi.update(candle("11", "11", "11", "100")).isPresent());
    }

    @Test
    public void calculatesKnownValue() {
        MfiIndicator mfi = new MfiIndicator(2);

        mfi.update(candle("10", "10", "10", "100"));            // seed, typical 10
        mfi.update(candle("12", "12", "12", "100"));            // up -> positive flow 1200
        Optional<BigDecimal> result = mfi.update(candle("11", "11", "11", "100")); // down -> negative 1100

        // ratio = 1200/1100 -> MFI = 100 - 100/(1 + 1.09090909) = 52.17
        assertEquals(new BigDecimal("52.17"), result.orElseThrow().setScale(2, RoundingMode.HALF_EVEN));
    }

    @Test
    public void weightsFlowByVolumeNotOnlyByPrice() {
        MfiIndicator mfi = new MfiIndicator(2);

        mfi.update(candle("10", "10", "10", "100"));
        mfi.update(candle("12", "12", "12", "1000"));           // positive flow 12000
        Optional<BigDecimal> result = mfi.update(candle("11", "11", "11", "100")); // negative flow 1100

        // Same price path as the previous test, but a 10x heavier up candle -> far more overbought
        assertTrue(result.orElseThrow().compareTo(new BigDecimal("90")) > 0);
    }

    @Test
    public void zeroNegativeFlowWithPositiveFlowSaturatesAt100() {
        MfiIndicator mfi = new MfiIndicator(2);

        mfi.update(candle("10", "10", "10", "100"));
        mfi.update(candle("11", "11", "11", "100"));
        Optional<BigDecimal> result = mfi.update(candle("12", "12", "12", "100"));

        assertEquals(0, result.orElseThrow().compareTo(new BigDecimal("100")));
    }

    @Test
    public void flatSeriesWithNoFlowEitherWayIsNeutral() {
        MfiIndicator mfi = new MfiIndicator(2);

        mfi.update(candle("10", "10", "10", "100"));
        mfi.update(candle("10", "10", "10", "100"));
        Optional<BigDecimal> result = mfi.update(candle("10", "10", "10", "100"));

        // Both flow sums are zero: no pressure to report, so neutral instead of a saturated 100
        assertEquals(0, result.orElseThrow().compareTo(new BigDecimal("50")));
    }

    @Test
    public void zeroVolumeGivesNoFlowAndStaysNeutral() {
        MfiIndicator mfi = new MfiIndicator(2);

        mfi.update(candle("10", "8", "9", "0"));
        mfi.update(candle("12", "10", "11", "0"));
        Optional<BigDecimal> result = mfi.update(candle("11", "9", "10", "0"));

        assertEquals(0, result.orElseThrow().compareTo(new BigDecimal("50")));
    }

    @Test
    public void highEqualToLowIsHandled() {
        MfiIndicator mfi = new MfiIndicator(2);

        mfi.update(candle("10", "10", "10", "100"));
        mfi.update(candle("9", "9", "9", "100"));
        Optional<BigDecimal> result = mfi.update(candle("8", "8", "8", "100"));

        // Only negative flow -> fully oversold
        assertEquals(0, result.orElseThrow().compareTo(BigDecimal.ZERO));
    }

    private CandleEvent candle(String high, String low, String close, String volume) {
        Instant openTime = Instant.parse("2026-09-08T10:00:00Z");

        return new CandleEvent(
                "BTC/USD",
                openTime,
                openTime.plusSeconds(60),
                new BigDecimal(close),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(close),
                new BigDecimal(volume),
                10
        );
    }
}
