package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.TradeFlow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AbsorptionTest {

    // |delta| >= 0.3, volume >= 1.5x average, price may move at most 0.25 ATR with the aggression
    private static final BigDecimal ATR = BigDecimal.TEN;          // allowed move = 2.5
    private static final BigDecimal HIGH_VOLUME = new BigDecimal("2");

    @Test
    public void heavySellingThatDoesNotDropPriceIsBuyAbsorption() {
        Absorption absorption = absorption(15);

        // 60% net selling, price only slipped 1 (less than 2.5)
        var value = absorption.update(candle("100", "99"), new BigDecimal("-0.6"), HIGH_VOLUME, ATR);

        assertEquals(0, value.strength().compareTo(new BigDecimal("0.6")));
    }

    @Test
    public void heavyBuyingThatDoesNotLiftPriceIsSellAbsorption() {
        Absorption absorption = absorption(15);

        var value = absorption.update(candle("100", "101"), new BigDecimal("0.5"), HIGH_VOLUME, ATR);

        assertEquals(0, value.strength().compareTo(new BigDecimal("-0.5")));
    }

    @Test
    public void aggressionThatMovesPriceIsNotAbsorption() {
        Absorption absorption = absorption(15);

        // the selling pushed price down 5, twice the allowed move: it worked, nothing was absorbed
        var value = absorption.update(candle("100", "95"), new BigDecimal("-0.6"), HIGH_VOLUME, ATR);

        assertEquals(0, value.strength().compareTo(BigDecimal.ZERO));
    }

    @Test
    public void weakDeltaLowVolumeOrMissingDataIsNotAbsorption() {
        Absorption absorption = absorption(15);

        assertEquals(0, absorption.update(candle("100", "100"), new BigDecimal("-0.1"), HIGH_VOLUME, ATR)
                .strength().compareTo(BigDecimal.ZERO));
        assertEquals(0, absorption.update(candle("100", "100"), new BigDecimal("-0.6"), BigDecimal.ONE, ATR)
                .strength().compareTo(BigDecimal.ZERO));
        // ATR still warming up
        assertEquals(0, absorption.update(candle("100", "100"), new BigDecimal("-0.6"), HIGH_VOLUME, BigDecimal.ZERO)
                .strength().compareTo(BigDecimal.ZERO));
        // candle without aggressor data
        assertEquals(0, absorption.update(candleWithoutFlow(), new BigDecimal("-0.6"), HIGH_VOLUME, ATR)
                .strength().compareTo(BigDecimal.ZERO));
    }

    @Test
    public void windowSumDropsExpiredCandles() {
        Absorption absorption = absorption(2);

        assertEquals(0, absorption.update(candle("100", "100"), new BigDecimal("-0.6"), HIGH_VOLUME, ATR)
                .windowSum().compareTo(new BigDecimal("0.6")));
        assertEquals(0, absorption.update(candle("100", "100"), new BigDecimal("0.5"), HIGH_VOLUME, ATR)
                .windowSum().compareTo(new BigDecimal("0.1")));
        // the +0.6 leaves the 2-candle window: -0.5 + 0.4
        assertEquals(0, absorption.update(candle("100", "100"), new BigDecimal("-0.4"), HIGH_VOLUME, ATR)
                .windowSum().compareTo(new BigDecimal("-0.1")));
    }

    private Absorption absorption(int window) {
        return new Absorption(new BigDecimal("0.3"), new BigDecimal("1.5"), new BigDecimal("0.25"), window);
    }

    private CandleEvent candle(String open, String close) {
        return candle(open, close, TradeFlow.fromKline(new BigDecimal("10"), new BigDecimal("5")));
    }

    private CandleEvent candleWithoutFlow() {
        return candle("100", "100", TradeFlow.none());
    }

    private CandleEvent candle(String open, String close, TradeFlow flow) {
        Instant openTime = Instant.parse("2026-09-08T10:00:00Z");
        BigDecimal high = new BigDecimal(open).max(new BigDecimal(close));
        BigDecimal low = new BigDecimal(open).min(new BigDecimal(close));
        return new CandleEvent("BTC/USD", openTime, openTime.plusSeconds(60),
                new BigDecimal(open), high, low, new BigDecimal(close),
                new BigDecimal("10"), 10, flow);
    }
}
