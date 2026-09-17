package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.resilience.BinanceResilience;
import dev.romeo.btctradingengine.resilience.BinanceResilienceSettings;
import dev.romeo.btctradingengine.model.AggressorSide;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.model.TradeFlow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BinanceAggTradeArchiveTest {

    private static final BigDecimal LARGE = new BigDecimal("100000");
    private static final long MINUTE = 60_000;

    @Test
    public void detectsMicrosecondAndMillisecondTimestamps() {
        // 2026 dumps use microseconds (16 digits), older ones milliseconds (13 digits)
        assertEquals(1788998400683L, BinanceAggTradeArchive.toEpochMillis(1788998400683320L));
        assertEquals(1788998400683L, BinanceAggTradeArchive.toEpochMillis(1788998400683L));
    }

    @Test
    public void bucketsUseTheSameInclusiveBoundaryAsTheLiveAggregator() {
        assertEquals(0, BinanceAggTradeArchive.bucketIndex(new BigDecimal("999.99")));
        assertEquals(1, BinanceAggTradeArchive.bucketIndex(new BigDecimal("1000")));
        assertEquals(3, BinanceAggTradeArchive.bucketIndex(new BigDecimal("100000")));
        assertEquals(4, BinanceAggTradeArchive.bucketIndex(new BigDecimal("2500000")));
    }

    @Test
    public void dumpAggregationMatchesTheLiveCandleAggregator() throws Exception {
        // (price, quantity, isBuyerMaker) - notionals 200, 150000 (large), 400, 100000 (large, inclusive)
        String[][] trades = {
                {"100", "2", "False"},
                {"100", "1500", "False"},
                {"100", "4", "True"},
                {"100", "1000", "True"},
        };
        long openTimeMs = 1788998400000L;

        StringBuilder dump = new StringBuilder();
        for (int i = 0; i < trades.length; i++) {
            long micros = (openTimeMs + 1000L * (i + 1)) * 1000;
            dump.append(i).append(',').append(trades[i][0]).append(',').append(trades[i][1])
                    .append(",0,0,").append(micros).append(',').append(trades[i][2]).append(",True\n");
        }
        TradeFlow fromDump = BinanceAggTradeArchive.aggregate(new StringReader(dump.toString()), MINUTE)
                .get(Instant.ofEpochMilli(openTimeMs)).toTradeFlow(LARGE);

        List<CandleEvent> candles = new ArrayList<>();
        CandleAggregator aggregator = new CandleAggregator(Duration.ofMinutes(1), candles::add, LARGE);
        for (int i = 0; i < trades.length; i++) {
            Instant time = Instant.ofEpochMilli(openTimeMs + 1000L * (i + 1));
            aggregator.onEvent(new NormalizedPriceEvent("BTCUSDT", new BigDecimal(trades[i][0]), time, time,
                    new BigDecimal(trades[i][1]), AggressorSide.fromBuyerMaker(Boolean.parseBoolean(trades[i][2]))));
        }
        Instant rollover = Instant.ofEpochMilli(openTimeMs + MINUTE);
        aggregator.onEvent(new NormalizedPriceEvent("BTCUSDT", BigDecimal.ONE, rollover, rollover, BigDecimal.ONE, AggressorSide.BUY));
        TradeFlow live = candles.get(0).flow();

        assertEquals(TradeFlow.Source.TRADES, fromDump.source());
        assertEquals(0, fromDump.takerBuyVolume().compareTo(live.takerBuyVolume()));
        assertEquals(0, fromDump.takerSellVolume().compareTo(live.takerSellVolume()));
        assertEquals(0, fromDump.largeBuyVolume().compareTo(live.largeBuyVolume()));
        assertEquals(0, fromDump.largeSellVolume().compareTo(live.largeSellVolume()));
        assertEquals(0, fromDump.largeBuyVolume().compareTo(new BigDecimal("1500")));
        assertEquals(0, fromDump.largeSellVolume().compareTo(new BigDecimal("1000")));
    }

    @Test
    public void thresholdBetweenEdgesRoundsUpToTheNextEdge() throws Exception {
        // one 50k buy: small against a 100k threshold, and still small against 20k (next edge is 100k)
        String dump = "1,100,500,0,0,1788998401000000,False,True\n";
        BinanceAggTradeArchive.SizeBuckets buckets = BinanceAggTradeArchive.aggregate(new StringReader(dump), MINUTE)
                .get(Instant.ofEpochMilli(1788998400000L));

        assertEquals(0, buckets.toTradeFlow(new BigDecimal("20000")).largeBuyVolume().compareTo(BigDecimal.ZERO));
        assertEquals(0, buckets.toTradeFlow(new BigDecimal("10000")).largeBuyVolume().compareTo(new BigDecimal("500")));
    }

    @Test
    public void loadsACachedDayWithoutTouchingTheNetwork(@TempDir Path cacheDir) throws Exception {
        String dump = "1,100,2,0,0,1788998401000000,False,True\n2,100,3,0,0,1788998461000000,True,True\n";
        Map<Instant, BinanceAggTradeArchive.SizeBuckets> aggregate =
                BinanceAggTradeArchive.aggregate(new StringReader(dump), MINUTE);
        Files.writeString(cacheDir.resolve("BTCUSDT-aggTrades-2026-09-10-60000ms.csv"),
                BinanceAggTradeArchive.formatAggregate(aggregate));
        // Unroutable base URL: a network call would fail the test
        BinanceAggTradeArchive archive = new BinanceAggTradeArchive(new BinanceResilience(BinanceResilienceSettings.defaults()), "http://127.0.0.1:9", cacheDir, Duration.ofMinutes(1));

        Map<Instant, BinanceAggTradeArchive.SizeBuckets> loaded =
                archive.load("BTCUSDT", LocalDate.parse("2026-09-10"), LocalDate.parse("2026-09-10"));

        assertEquals(aggregate, loaded);
        assertEquals(0, loaded.get(Instant.ofEpochMilli(1788998460000L)).toTradeFlow(LARGE)
                .takerSellVolume().compareTo(new BigDecimal("3")));
    }
}
