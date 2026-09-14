package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.TradeFlow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class VpinTest {

    @Test
    public void reportsZeroUntilEnoughBucketsHaveCompleted() {
        // 2 buckets, each sized as 1 average candle of volume
        Vpin vpin = new Vpin(2, 1);

        // all 10 BTC bought: one full bucket with imbalance 1 - but only 1 of the 2 buckets
        assertEquals(0, vpin.update(candle(kline("10", "10"))).compareTo(BigDecimal.ZERO));
        // balanced bucket: imbalance 0, now (1 + 0) / 2
        assertEquals(0, vpin.update(candle(kline("10", "5"))).compareTo(new BigDecimal("0.5")));
    }

    @Test
    public void splitsCandlesAcrossBucketsAndDropsExpiredOnes() {
        Vpin vpin = new Vpin(2, 1);

        vpin.update(candle(kline("10", "10")));                 // bucket of 10: imbalance 1
        // average volume is now 20: one balanced bucket of 20, then 10 of a new bucket (5 buy / 5 sell)
        BigDecimal afterSplit = vpin.update(candle(kline("30", "15")));
        assertEquals(0, afterSplit.compareTo(new BigDecimal("0.5")));

        // fills the open bucket with 10 more bought: 15 buy / 5 sell over 20 = imbalance 0.5.
        // The first bucket (imbalance 1) leaves the window: (0 + 0.5) / 2
        BigDecimal afterExpiry = vpin.update(candle(kline("10", "10")));
        assertEquals(0, afterExpiry.compareTo(new BigDecimal("0.25")));
    }

    @Test
    public void candlesWithoutFlowDataAreSkipped() {
        Vpin vpin = new Vpin(2, 1);
        vpin.update(candle(kline("10", "10")));
        vpin.update(candle(kline("10", "5")));

        // no aggressor data: neither a bucket nor a change in the average candle volume
        assertEquals(0, vpin.update(candle(TradeFlow.none())).compareTo(new BigDecimal("0.5")));
    }

    private TradeFlow kline(String volume, String takerBuyVolume) {
        return TradeFlow.fromKline(new BigDecimal(volume), new BigDecimal(takerBuyVolume));
    }

    private CandleEvent candle(TradeFlow flow) {
        Instant openTime = Instant.parse("2026-09-08T10:00:00Z");
        BigDecimal volume = flow.takerBuyVolume().add(flow.takerSellVolume());

        return new CandleEvent(
                "BTC/USD",
                openTime,
                openTime.plusSeconds(60),
                new BigDecimal("100"),
                new BigDecimal("101"),
                new BigDecimal("99"),
                new BigDecimal("100"),
                volume,
                10,
                flow
        );
    }
}
