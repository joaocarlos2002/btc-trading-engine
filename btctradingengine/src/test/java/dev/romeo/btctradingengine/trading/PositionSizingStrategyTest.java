package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Issue #111: entry size comes from a configurable strategy; the default keeps the fixed 50%. */
public class PositionSizingStrategyTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final BigDecimal BALANCE = new BigDecimal("1000");
    private static final BigDecimal PRICE = new BigDecimal("100000");

    private static PositionSizingStrategy.SizingContext context(BigDecimal atr, PositionSizingStrategy.TradeStats stats) {
        return new PositionSizingStrategy.SizingContext(BALANCE, PRICE, new BigDecimal("1.5"), atr, stats);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static PositionSizingStrategy named(String name) {
        return PositionSizingStrategy.named(name, new BigDecimal("0.5"), new BigDecimal("1.0"),
                new BigDecimal("2.0"), new BigDecimal("0.25"), 3);
    }

    @Test
    public void defaultIsHalfTheBalance() {
        assertAmount("500", named("fixed").allocate(context(null, PositionSizingStrategy.TradeStats.EMPTY)));
        assertAmount("500", named("").allocate(context(null, PositionSizingStrategy.TradeStats.EMPTY)));
    }

    @Test
    public void atrRiskSizesByVolatility() {
        // Risk 10 USDT on a 2 x 500 = 1000 stop distance: 0.01 BTC = 1000 USDT, capped at 500
        assertAmount("500", named("atr").allocate(context(new BigDecimal("500"), PositionSizingStrategy.TradeStats.EMPTY)));
        // 2 x 2000 = 4000: 10 / 4000 = 0.0025 BTC = 250 USDT
        assertAmount("250", named("atr").allocate(context(new BigDecimal("2000"), PositionSizingStrategy.TradeStats.EMPTY)));
        // No ATR yet: the 1.5% stop (1500) is the distance: 10 / 1500 = 0.00666666 BTC
        PositionSizingStrategy uncapped = PositionSizingStrategy.atrRisk(new BigDecimal("0.01"), new BigDecimal("2"), BigDecimal.ONE);
        assertAmount("666.666", uncapped.allocate(context(null, PositionSizingStrategy.TradeStats.EMPTY)));
    }

    @Test
    public void kellyUsesTheFallbackUntilEnoughTrades() {
        PositionSizingStrategy.TradeStats few = new PositionSizingStrategy.TradeStats(2, new BigDecimal("0.9"),
                new BigDecimal("2"), new BigDecimal("1"));
        assertAmount("500", named("kelly").allocate(context(null, few)));
    }

    @Test
    public void kellyScalesAndClampsTheEdge() {
        // p = 0.6, b = 2/1: f* = 0.6 - 0.4 / 2 = 0.4; quarter Kelly = 0.1 of the balance
        PositionSizingStrategy.TradeStats edge = new PositionSizingStrategy.TradeStats(40, new BigDecimal("0.6"),
                new BigDecimal("2"), new BigDecimal("1"));
        assertAmount("100", named("kelly").allocate(context(null, edge)));
        // Negative edge: p = 0.3, b = 1: f* = -0.4 -> nothing
        PositionSizingStrategy.TradeStats losing = new PositionSizingStrategy.TradeStats(40, new BigDecimal("0.3"),
                new BigDecimal("1"), new BigDecimal("1"));
        assertAmount("0", named("kelly").allocate(context(null, losing)));
    }

    @Test
    public void statsIgnoreFailedEntries() {
        List<Position> closed = new ArrayList<>();
        closed.add(closed("101000", ExitReason.TARGET_HIT));
        closed.add(closed("99000", ExitReason.STOP_LOSS));
        closed.add(closed("100000", ExitReason.ORDER_FAILED));

        PositionSizingStrategy.TradeStats stats = PositionSizingStrategy.TradeStats.of(closed);

        assertEquals(2, stats.trades());
        assertAmount("0.5", stats.winRate());
        assertAmount("1", stats.averageWinPercent());
        assertAmount("1", stats.averageLossPercent());
    }

    @Test
    public void rejectsUnknownNamesAndBadFractions() {
        assertThrows(IllegalArgumentException.class, () -> named("martingale"));
        assertThrows(IllegalArgumentException.class, () -> PositionSizingStrategy.fixedFraction(new BigDecimal("1.5")));
    }

    @Test
    public void positionManagerSendsTheStrategyQuantity() {
        List<BigDecimal> bought = new ArrayList<>();
        BinanceOrderExecutor exchange = new BinanceOrderExecutor("test-key", "test-secret") {
            @Override
            public OrderResult executeBuyMarket(String symbol, BigDecimal quantity, String clientOrderId) {
                bought.add(quantity);
                return new OrderResult(true, "1", quantity, PRICE, null);
            }

            @Override
            public SymbolFilters getSymbolFilters(String symbol) {
                return new SymbolFilters(symbol, new BigDecimal("5"), new BigDecimal("0.00001"),
                        new BigDecimal("1000"), new BigDecimal("0.00001"));
            }

            @Override
            public BalanceResult getBalance(String asset) {
                return new BalanceResult(true, BALANCE, BigDecimal.ZERO, BALANCE, null);
            }
        };
        PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
        manager.setOrderIoExecutor(Runnable::run);
        manager.setRealTradingMode(exchange, new PortfolioManager(BALANCE, new BigDecimal("50")), "BTCUSDT");
        manager.markReconciliationComplete();
        manager.setPositionSizing(PositionSizingStrategy.fixedFraction(new BigDecimal("0.2")));

        manager.openManualBuy(PRICE, NOW);

        assertAmount("0.002", bought.getFirst());
    }

    private static Position closed(String exit, ExitReason reason) {
        Position position = new Position("P", Signal.BUY, PRICE, NOW, new BigDecimal("2"), new BigDecimal("1.5"));
        position.close(new BigDecimal(exit), NOW, reason);
        return position;
    }
}
