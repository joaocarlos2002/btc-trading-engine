package dev.romeo.btctradingengine.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.romeo.btctradingengine.model.AggressorSide;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.model.TradeFlow;
import dev.romeo.btctradingengine.orderbook.BinanceDepthClient;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DatabaseWriter, the candle and tick readers and the snapshot writers against real PostgreSQL
 * (issue #103): the SQL, the ON CONFLICT target, NULL handling and NUMERIC round trips that the
 * unit tests cannot see.
 */
@Testcontainers(disabledWithoutDocker = true)
class DatabaseWriterIT {
    private static final String SYMBOL = "BTCUSDT";
    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    @BeforeEach
    void emptyTables() throws SQLException {
        PostgresTestDatabase.truncateAll();
    }

    private static CandleEvent candle(Instant open, String close, TradeFlow flow) {
        return new CandleEvent(
                SYMBOL,
                open,
                open.plus(Duration.ofMinutes(15)).minusMillis(1),
                new BigDecimal("60000.10"),
                new BigDecimal("60500.12345678"),
                new BigDecimal("59900"),
                new BigDecimal(close),
                new BigDecimal("12.5"),
                42,
                flow);
    }

    private static NormalizedPriceEvent tick(Instant at, String price, AggressorSide side) {
        return new NormalizedPriceEvent(
                SYMBOL, new BigDecimal(price), at, at, new BigDecimal("0.00123"), side);
    }

    private static void writeAndStop(DatabaseWriter writer, Runnable events) {
        writer.start();
        events.run();
        writer.stop(); // drains the queue before returning
    }

    @Test
    void candlesRoundTripWithFlowAndDuplicatesAreIgnored() throws Exception {
        DatabaseWriter writer = new DatabaseWriter(PostgresTestDatabase.dataSource());
        CandleEvent none = candle(T0, "60100", TradeFlow.none());
        CandleEvent kline =
                candle(
                        T0.plus(Duration.ofMinutes(15)),
                        "60200",
                        TradeFlow.fromKline(new BigDecimal("12.5"), new BigDecimal("7.5")));
        CandleEvent trades =
                candle(
                        T0.plus(Duration.ofMinutes(30)),
                        "60300",
                        TradeFlow.fromTrades(
                                new BigDecimal("8"),
                                new BigDecimal("4.5"),
                                new BigDecimal("2"),
                                new BigDecimal("1")));
        CandleEvent duplicate = candle(T0, "99999", TradeFlow.none());

        writeAndStop(
                writer,
                () -> {
                    writer.onEvent(none);
                    writer.onEvent(kline);
                    writer.onEvent(trades);
                    writer.onEvent(duplicate);
                });

        assertEquals(3, PostgresTestDatabase.count("SELECT count(*) FROM candles"));
        // The first candle of an open time wins; the duplicate is dropped by ON CONFLICT DO NOTHING
        assertEquals(
                0,
                new BigDecimal("60100")
                        .compareTo(
                                (BigDecimal)
                                        PostgresTestDatabase.queryValue(
                                                "SELECT close FROM candles WHERE open_time_ms = ?",
                                                T0.toEpochMilli())));
        // NULL, not 0, when the candle has no flow
        assertNull(
                PostgresTestDatabase.queryValue(
                        "SELECT flow_source FROM candles WHERE open_time_ms = ?",
                        T0.toEpochMilli()));

        List<CandleEvent> loaded =
                new DatabaseCandleReader(PostgresTestDatabase.dataSource())
                        .loadRecentClosedCandles(SYMBOL, 10);
        assertEquals(3, loaded.size());
        assertEquals(T0, loaded.get(0).openTime(), "oldest first");
        assertEquals(TradeFlow.Source.NONE, loaded.get(0).flow().source());
        assertEquals(0, new BigDecimal("60500.12345678").compareTo(loaded.get(0).high()));
        assertEquals(42, loaded.get(0).tickCount());

        TradeFlow klineFlow = loaded.get(1).flow();
        assertEquals(TradeFlow.Source.KLINE, klineFlow.source());
        assertEquals(0, new BigDecimal("7.5").compareTo(klineFlow.takerBuyVolume()));
        assertEquals(0, new BigDecimal("5").compareTo(klineFlow.takerSellVolume()));

        TradeFlow tradesFlow = loaded.get(2).flow();
        assertEquals(TradeFlow.Source.TRADES, tradesFlow.source());
        assertEquals(0, new BigDecimal("2").compareTo(tradesFlow.largeBuyVolume()));
        assertEquals(0, new BigDecimal("1").compareTo(tradesFlow.largeSellVolume()));
    }

    @Test
    void candleReaderReturnsTheMostRecentCandlesOldestFirst() throws Exception {
        DatabaseWriter writer = new DatabaseWriter(PostgresTestDatabase.dataSource());
        writeAndStop(
                writer,
                () -> {
                    for (int i = 0; i < 5; i++) {
                        writer.onEvent(
                                candle(
                                        T0.plus(Duration.ofMinutes(15L * i)),
                                        String.valueOf(60000 + i),
                                        TradeFlow.none()));
                    }
                });

        List<CandleEvent> lastTwo =
                new DatabaseCandleReader(PostgresTestDatabase.dataSource())
                        .loadRecentClosedCandles(SYMBOL, 2);
        assertEquals(
                List.of(T0.plus(Duration.ofMinutes(45)), T0.plus(Duration.ofMinutes(60))),
                lastTwo.stream().map(CandleEvent::openTime).toList());
    }

    @Test
    void ticksRoundTripInArrivalOrderWithinTheWindow() throws Exception {
        DatabaseWriter writer = new DatabaseWriter(PostgresTestDatabase.dataSource());
        writeAndStop(
                writer,
                () -> {
                    writer.onEvent(tick(T0, "60000.01", AggressorSide.BUY));
                    writer.onEvent(
                            tick(
                                    T0,
                                    "60000.02",
                                    AggressorSide.SELL)); // same millisecond: id breaks the tie
                    writer.onEvent(tick(T0.plusMillis(5), "60000.03", AggressorSide.UNKNOWN));
                    writer.onEvent(
                            tick(
                                    T0.plusSeconds(60),
                                    "60000.04",
                                    AggressorSide.BUY)); // outside the window
                });

        assertEquals(4, PostgresTestDatabase.count("SELECT count(*) FROM ticks"));
        assertEquals(
                1,
                PostgresTestDatabase.count(
                        "SELECT count(*) FROM ticks WHERE aggressor_side IS NULL"));

        List<NormalizedPriceEvent> ticks =
                new DatabaseTickReader(PostgresTestDatabase.dataSource())
                        .loadTicks(SYMBOL, T0, T0.plusSeconds(60));
        assertEquals(3, ticks.size());
        assertEquals(
                List.of("60000.01", "60000.02", "60000.03"),
                ticks.stream().map(t -> t.price().stripTrailingZeros().toPlainString()).toList());
        assertEquals(
                List.of(AggressorSide.BUY, AggressorSide.SELL, AggressorSide.UNKNOWN),
                ticks.stream().map(NormalizedPriceEvent::aggressorSide).toList());
        assertEquals(0, new BigDecimal("0.00123").compareTo(ticks.getFirst().quantity()));
    }

    @Test
    void oneBadRowDoesNotLoseTheRestOfTheBatch() throws Exception {
        DatabaseWriter writer = new DatabaseWriter(PostgresTestDatabase.dataSource());
        // VARCHAR(20) symbol: PostgreSQL rejects this row, which rolls the whole batch back
        NormalizedPriceEvent bad =
                new NormalizedPriceEvent(
                        "A_SYMBOL_LONGER_THAN_TWENTY",
                        BigDecimal.ONE,
                        T0,
                        T0,
                        BigDecimal.ONE,
                        AggressorSide.BUY);
        writeAndStop(
                writer,
                () -> {
                    writer.onEvent(tick(T0, "1", AggressorSide.BUY));
                    writer.onEvent(bad);
                    writer.onEvent(tick(T0.plusMillis(1), "2", AggressorSide.SELL));
                    writer.onEvent(candle(T0, "60000", TradeFlow.none()));
                });

        assertEquals(2, PostgresTestDatabase.count("SELECT count(*) FROM ticks"));
        assertEquals(1, PostgresTestDatabase.count("SELECT count(*) FROM candles"));
    }

    @Test
    void snapshotWritersStoreNullReadingsAsNull() throws Exception {
        new DerivativesSnapshotWriter(PostgresTestDatabase.dataSource())
                .write(SYMBOL, T0, new BigDecimal("81234.123"), null, new BigDecimal("0.0001"));
        new OrderBookSnapshotWriter(PostgresTestDatabase.dataSource())
                .write(
                        SYMBOL,
                        T0,
                        new BinanceDepthClient.DepthSnapshot(
                                new BigDecimal("10.5"), new BigDecimal("9.5"), 100));

        assertEquals(
                1,
                PostgresTestDatabase.count(
                        "SELECT count(*) FROM derivatives_snapshots WHERE long_short_ratio IS NULL"));
        assertEquals(
                0,
                new BigDecimal("0.0001")
                        .compareTo(
                                (BigDecimal)
                                        PostgresTestDatabase.queryValue(
                                                "SELECT funding_rate FROM derivatives_snapshots")));
        assertEquals(
                100,
                ((Number)
                                PostgresTestDatabase.queryValue(
                                        "SELECT levels FROM order_book_snapshots"))
                        .intValue());
        assertTrue(
                PostgresTestDatabase.count(
                                "SELECT count(*) FROM order_book_snapshots WHERE bid_volume = 10.5")
                        == 1);
    }
}
