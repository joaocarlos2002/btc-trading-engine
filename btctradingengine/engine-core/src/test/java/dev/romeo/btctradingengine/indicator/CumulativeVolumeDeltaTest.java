package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.TradeFlow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CumulativeVolumeDeltaTest {

    @Test
    public void splitsTheCandleIntoDeltaAndRatio() {
        CumulativeVolumeDelta cvd = new CumulativeVolumeDelta(10);

        // 7 buy aggression, 3 sell aggression
        CumulativeVolumeDelta.CvdValue value = cvd.update(candle(kline("10", "7")));

        assertEquals(0, value.volumeDelta().compareTo(new BigDecimal("4")));
        assertEquals(0, value.deltaRatio().compareTo(new BigDecimal("0.4")));
        assertEquals(0, value.cvd().compareTo(new BigDecimal("4")));
        assertEquals(0, value.cvdRatio().compareTo(new BigDecimal("0.4")));
    }

    @Test
    public void rollingWindowDropsExpiredCandles() {
        CumulativeVolumeDelta cvd = new CumulativeVolumeDelta(2);

        cvd.update(candle(kline("10", "8")));                                       // delta +6
        CumulativeVolumeDelta.CvdValue full = cvd.update(candle(kline("10", "3"))); // delta -4
        assertEquals(0, full.cvd().compareTo(new BigDecimal("2")));
        assertEquals(0, full.cvdRatio().compareTo(new BigDecimal("0.1")));

        // the +6 candle leaves the window: -4 + 0
        CumulativeVolumeDelta.CvdValue slid = cvd.update(candle(kline("10", "5")));
        assertEquals(0, slid.cvd().compareTo(new BigDecimal("-4")));
        assertEquals(0, slid.cvdRatio().compareTo(new BigDecimal("-0.2")));
    }

    @Test
    public void candlesWithoutFlowDoNotDiluteTheRatio() {
        CumulativeVolumeDelta cvd = new CumulativeVolumeDelta(3);

        cvd.update(candle(kline("10", "8")));                                        // delta +6
        CumulativeVolumeDelta.CvdValue gap = cvd.update(candle(TradeFlow.none()));

        assertEquals(0, gap.deltaRatio().compareTo(BigDecimal.ZERO));
        assertEquals(0, gap.cvd().compareTo(new BigDecimal("6")));
        // still 6 / 10: the flow-less candle added no volume to the denominator
        assertEquals(0, gap.cvdRatio().compareTo(new BigDecimal("0.6")));
    }

    @Test
    public void sizeSplitOnlyCountsCandlesBuiltFromTrades() {
        CumulativeVolumeDelta cvd = new CumulativeVolumeDelta(5);

        // 6 buy (5 of it large), 2 sell (1 of it large)
        CumulativeVolumeDelta.CvdValue trades = cvd.update(candle(TradeFlow.fromTrades(
                new BigDecimal("6"), new BigDecimal("2"), new BigDecimal("5"), new BigDecimal("1"))));
        assertEquals(0, trades.largeCvd().compareTo(new BigDecimal("4")));
        assertEquals(0, trades.largeVolumeShare().compareTo(new BigDecimal("0.75")));

        // a kline candle has no sizes: it moves cvd, but must not dilute the large share
        CumulativeVolumeDelta.CvdValue mixed = cvd.update(candle(kline("10", "5")));
        assertEquals(0, mixed.largeCvd().compareTo(new BigDecimal("4")));
        assertEquals(0, mixed.largeVolumeShare().compareTo(new BigDecimal("0.75")));
        assertEquals(0, mixed.cvd().compareTo(new BigDecimal("4")));
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
