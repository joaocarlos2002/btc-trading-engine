package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PriceActionTest {

    @Test
    public void splitsTheCandleRangeIntoBodyAndWicks() {
        PriceAction priceAction = new PriceAction(10, 1);

        PriceAction.PriceActionValue value = priceAction.update(candle("11", "15", "10", "13"));

        // range 5: body 13-11 = 2, upper wick 15-13 = 2, lower wick 11-10 = 1
        assertEquals(0, value.bodyRatio().compareTo(new BigDecimal("0.4")));
        assertEquals(0, value.upperWickRatio().compareTo(new BigDecimal("0.4")));
        assertEquals(0, value.lowerWickRatio().compareTo(new BigDecimal("0.2")));
    }

    @Test
    public void flatCandleHasNoAnatomyInsteadOfDividingByZero() {
        PriceAction priceAction = new PriceAction(10, 1);

        PriceAction.PriceActionValue value = priceAction.update(candle("100", "100", "100", "100"));

        assertEquals(0, value.bodyRatio().compareTo(BigDecimal.ZERO));
        assertEquals(0, value.upperWickRatio().compareTo(BigDecimal.ZERO));
        assertEquals(0, value.lowerWickRatio().compareTo(BigDecimal.ZERO));
    }

    @Test
    public void countsBullishAndBearishStreaksAndResetsOnDoji() {
        PriceAction priceAction = new PriceAction(10, 1);
        List<Integer> streaks = new ArrayList<>();

        streaks.add(priceAction.update(candle("10", "12", "9", "11")).candleStreak());   // up
        streaks.add(priceAction.update(candle("11", "13", "10", "12")).candleStreak());  // up
        streaks.add(priceAction.update(candle("12", "13", "10", "11")).candleStreak());  // down
        streaks.add(priceAction.update(candle("11", "12", "9", "10")).candleStreak());   // down
        streaks.add(priceAction.update(candle("10", "11", "8", "9")).candleStreak());    // down
        streaks.add(priceAction.update(candle("9", "10", "8", "9")).candleStreak());     // doji
        streaks.add(priceAction.update(candle("9", "11", "8", "10")).candleStreak());    // up

        assertEquals(List.of(1, 2, -1, -2, -3, 0, 1), streaks);
    }

    @Test
    public void previousCandleBreakNeedsTheCloseNotJustAWick() {
        PriceAction priceAction = new PriceAction(10, 1);

        assertEquals(0, priceAction.update(candle("10", "12", "8", "10")).previousCandleBreak());
        // close 13 above the previous high 12
        assertEquals(1, priceAction.update(candle("10", "13", "10", "13")).previousCandleBreak());
        // wick to 15 pierces the previous high 13, but the close 12 stays inside
        assertEquals(0, priceAction.update(candle("13", "15", "11", "12")).previousCandleBreak());
        // close 10 below the previous low 11
        assertEquals(-1, priceAction.update(candle("12", "12", "9", "10")).previousCandleBreak());
    }

    @Test
    public void measuresDistanceToTheHighAndLowOfThePreviousNCandles() {
        PriceAction priceAction = new PriceAction(2, 1);

        priceAction.update(candle("100", "110", "90", "100"));
        PriceAction.PriceActionValue warmingUp = priceAction.update(candle("100", "105", "95", "100"));
        assertEquals(0, warmingUp.recentHighDistance().compareTo(BigDecimal.ZERO));

        // previous 2 candles: highest high 110, lowest low 90, close 100
        PriceAction.PriceActionValue full = priceAction.update(candle("100", "100", "100", "100"));
        assertEquals(0, full.recentHighDistance().compareTo(new BigDecimal("-10")));
        assertEquals(0, full.recentLowDistance().compareTo(new BigDecimal("10")));

        // the 110/90 candle left the window: now 105 and 95
        PriceAction.PriceActionValue slid = priceAction.update(candle("100", "100", "100", "100"));
        assertEquals(0, slid.recentHighDistance().compareTo(new BigDecimal("-5")));
        assertEquals(0, slid.recentLowDistance().compareTo(new BigDecimal("5")));
    }

    @Test
    public void detectsHigherHighsAndLowerLowsOnlyAfterThePivotIsConfirmed() {
        PriceAction priceAction = new PriceAction(100, 1);

        priceAction.update(candle("9", "10", "8", "9"));                                   // 0
        PriceAction.PriceActionValue atPivot = priceAction.update(candle("10", "12", "9", "10")); // 1: high 12
        // no lookahead: the high at candle 1 is not a level until candle 2 prints below it
        assertEquals(0, atPivot.resistanceDistance().compareTo(BigDecimal.ZERO));

        PriceAction.PriceActionValue confirmed = priceAction.update(candle("10", "11", "7", "10")); // 2: low 7
        // resistance at 12, close 10: (12 - 10) / 10 * 100 = 20
        assertEquals(0, confirmed.resistanceDistance().compareTo(new BigDecimal("20")));
        assertEquals(0, confirmed.supportDistance().compareTo(BigDecimal.ZERO));
        assertEquals(0, confirmed.swingHighTrend());

        PriceAction.PriceActionValue brokeOut = priceAction.update(candle("12", "14", "8", "14")); // 3: high 14
        // role reversal: the broken 12 resistance is now the nearest support, (14 - 12) / 14 * 100
        assertEquals(new BigDecimal("14.29"), brokeOut.supportDistance().setScale(2, RoundingMode.HALF_EVEN));
        assertEquals(0, brokeOut.resistanceDistance().compareTo(BigDecimal.ZERO));

        PriceAction.PriceActionValue higherHigh = priceAction.update(candle("11", "13", "9", "11")); // 4
        assertEquals(1, higherHigh.swingHighTrend());
        assertEquals(0, higherHigh.swingLowTrend());

        priceAction.update(candle("9", "12", "6", "8"));                                    // 5: low 6
        PriceAction.PriceActionValue lowerLow = priceAction.update(candle("9", "13", "7", "10")); // 6
        assertEquals(1, lowerLow.swingHighTrend());
        assertEquals(-1, lowerLow.swingLowTrend());
    }

    @Test
    public void swingLevelsExpireAfterTheLookback() {
        PriceAction priceAction = new PriceAction(2, 1);

        priceAction.update(candle("9", "10", "8", "9"));
        priceAction.update(candle("10", "12", "9", "10"));     // swing high 12 at index 1
        priceAction.update(candle("10", "11", "9", "10"));
        PriceAction.PriceActionValue stillThere = priceAction.update(candle("10", "11", "9", "10"));
        assertEquals(0, stillThere.resistanceDistance().compareTo(new BigDecimal("20")));

        PriceAction.PriceActionValue expired = priceAction.update(candle("10", "11", "9", "10"));
        assertEquals(0, expired.resistanceDistance().compareTo(BigDecimal.ZERO));
    }

    private CandleEvent candle(String open, String high, String low, String close) {
        Instant openTime = Instant.parse("2026-09-08T10:00:00Z");

        return new CandleEvent(
                "BTC/USD",
                openTime,
                openTime.plusSeconds(60),
                new BigDecimal(open),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(close),
                new BigDecimal("1000"),
                10
        );
    }
}
