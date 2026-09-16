package dev.romeo.btctradingengine.derivatives;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BinanceFuturesClientTest {

    @Test
    public void parsesFundingRates() throws Exception {
        List<BinanceFuturesClient.TimedValue> rates = BinanceFuturesClient.parseFundingRates(
                "[{\"symbol\":\"BTCUSDT\",\"fundingTime\":1698768000000,\"fundingRate\":\"0.00010000\",\"markPrice\":\"34287.54\"}]");

        assertEquals(1, rates.size());
        assertEquals(Instant.ofEpochMilli(1698768000000L), rates.get(0).time());
        assertEquals(0, rates.get(0).value().compareTo(new BigDecimal("0.0001")));
    }

    @Test
    public void parsesOpenInterest() throws Exception {
        BinanceFuturesClient.TimedValue openInterest = BinanceFuturesClient.parseOpenInterest(
                "{\"openInterest\":\"10659.509\",\"symbol\":\"BTCUSDT\",\"time\":1589437530011}");

        assertEquals(Instant.ofEpochMilli(1589437530011L), openInterest.time());
        assertEquals(0, openInterest.value().compareTo(new BigDecimal("10659.509")));
    }

    @Test
    public void parsesLongShortRatioWithNumericOrStringTimestamp() throws Exception {
        List<BinanceFuturesClient.TimedValue> ratios = BinanceFuturesClient.parseLongShortRatios(
                "[{\"symbol\":\"BTCUSDT\",\"longShortRatio\":\"1.8105\",\"longAccount\":\"0.6442\",\"shortAccount\":\"0.3558\",\"timestamp\":1583139600000},"
                        + "{\"symbol\":\"BTCUSDT\",\"longShortRatio\":\"1.8200\",\"longAccount\":\"0.6454\",\"shortAccount\":\"0.3546\",\"timestamp\":\"1583139900000\"}]");

        assertEquals(Instant.ofEpochMilli(1583139600000L), ratios.get(0).time());
        assertEquals(0, ratios.get(0).value().compareTo(new BigDecimal("1.8105")));
        assertEquals(Instant.ofEpochMilli(1583139900000L), ratios.get(1).time());
    }

    @Test
    public void parsesKlineOpenTimeAndClose() throws Exception {
        List<BinanceFuturesClient.TimedValue> closes = BinanceFuturesClient.parseKlineCloses(
                "[[1499040000000,\"0.01634790\",\"0.80000000\",\"0.01575800\",\"0.01577100\",\"148976.11427815\","
                        + "1499644799999,\"2434.19055334\",308,\"1756.87402397\",\"28.46694368\",\"0\"]]");

        assertEquals(Instant.ofEpochMilli(1499040000000L), closes.get(0).time());
        assertEquals(0, closes.get(0).value().compareTo(new BigDecimal("0.01577100")));
    }
}
