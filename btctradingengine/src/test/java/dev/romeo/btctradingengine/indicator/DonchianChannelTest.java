package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DonchianChannelTest {

    @Test
    public void returnsEmptyDuringWarmup() {
        DonchianChannel donchian = new DonchianChannel(3);

        assertTrue(donchian.update(candle("10", "8", "9")).isEmpty());
        assertTrue(donchian.update(candle("12", "9", "11")).isEmpty());
        assertTrue(donchian.update(candle("11", "7", "10")).isPresent());
    }

    @Test
    public void tracksHighestHighAndLowestLowOfTheWindow() {
        DonchianChannel donchian = new DonchianChannel(3);

        donchian.update(candle("10", "8", "9"));
        donchian.update(candle("12", "9", "11"));
        Optional<DonchianChannel.DonchianValue> result = donchian.update(candle("11", "7", "9"));

        DonchianChannel.DonchianValue value = result.orElseThrow();
        assertEquals(0, value.upper().compareTo(new BigDecimal("12")));
        assertEquals(0, value.lower().compareTo(new BigDecimal("7")));
        // position = (9 - 7) / (12 - 7) = 0.4
        assertEquals(0, value.position().compareTo(new BigDecimal("0.4")));
    }

    @Test
    public void closeAtChannelBoundsGivesZeroAndOne() {
        DonchianChannel atTop = new DonchianChannel(2);
        atTop.update(candle("10", "8", "9"));
        Optional<DonchianChannel.DonchianValue> top = atTop.update(candle("12", "9", "12"));
        assertEquals(0, top.orElseThrow().position().compareTo(BigDecimal.ONE));

        DonchianChannel atBottom = new DonchianChannel(2);
        atBottom.update(candle("10", "8", "9"));
        Optional<DonchianChannel.DonchianValue> bottom = atBottom.update(candle("10", "7", "7"));
        assertEquals(0, bottom.orElseThrow().position().compareTo(BigDecimal.ZERO));
    }

    @Test
    public void flatChannelReportsMidpointInsteadOfDividingByZero() {
        DonchianChannel donchian = new DonchianChannel(2);

        donchian.update(candle("100", "100", "100"));
        Optional<DonchianChannel.DonchianValue> result = donchian.update(candle("100", "100", "100"));

        DonchianChannel.DonchianValue value = result.orElseThrow();
        assertEquals(0, value.upper().compareTo(value.lower()));
        assertEquals(0, value.position().compareTo(new BigDecimal("0.5")));
    }

    @Test
    public void expiredCandlesLeaveTheChannel() {
        DonchianChannel donchian = new DonchianChannel(2);

        donchian.update(candle("50", "1", "9"));    // extreme candle, must expire
        donchian.update(candle("12", "9", "11"));
        Optional<DonchianChannel.DonchianValue> result = donchian.update(candle("13", "10", "12"));

        DonchianChannel.DonchianValue value = result.orElseThrow();
        assertEquals(0, value.upper().compareTo(new BigDecimal("13")));
        assertEquals(0, value.lower().compareTo(new BigDecimal("9")));
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
