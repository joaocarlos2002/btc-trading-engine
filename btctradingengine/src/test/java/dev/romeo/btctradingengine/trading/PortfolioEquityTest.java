package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #81: the portfolio drawdown is measured on equity (USDT + base asset at market). */
public class PortfolioEquityTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    /** A tiny account: buys and sells move USDT and BTC like Binance would. */
    private static final class AccountExecutor extends BinanceOrderExecutor {
        BigDecimal usdt = new BigDecimal("1000");
        BigDecimal btc = BigDecimal.ZERO;
        BigDecimal fillPrice = new BigDecimal("100000");

        AccountExecutor() {
            super("test-key", "test-secret", "https://testnet.binance.vision", 10, 1000, 60000);
        }

        @Override
        public OrderResult executeBuyMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            usdt = usdt.subtract(quantity.multiply(fillPrice));
            btc = btc.add(quantity);
            return new OrderResult(true, "1", quantity, fillPrice, null);
        }

        @Override
        public OrderResult executeSellMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            usdt = usdt.add(quantity.multiply(fillPrice));
            btc = btc.subtract(quantity);
            return new OrderResult(true, "2", quantity, fillPrice, null);
        }

        @Override
        public SymbolFilters getSymbolFilters(String symbol) {
            return new SymbolFilters(symbol, new BigDecimal("5"), new BigDecimal("0.00001"),
                    new BigDecimal("1000"), new BigDecimal("0.00001"));
        }

        @Override
        public BalanceResult getBalance(String asset) {
            BigDecimal total = "BTC".equals(asset) ? btc : usdt;
            return new BalanceResult(true, total, BigDecimal.ZERO, total, null);
        }
    }

    private static NormalizedPriceEvent tick(String price) {
        return new NormalizedPriceEvent("BTCUSDT", new BigDecimal(price), NOW, NOW);
    }

    @Test
    public void buyingWithHalfTheBalanceDoesNotTriggerThePortfolioStop() {
        AccountExecutor executor = new AccountExecutor();
        PortfolioManager portfolio = new PortfolioManager(new BigDecimal("1000"), new BigDecimal("10"));
        // Wide stop so the position stays open while the price moves
        PositionManager manager = new PositionManager(new BigDecimal("50"), new BigDecimal("50"));
        manager.setOrderIoExecutor(Runnable::run);
        manager.setRealTradingMode(executor, portfolio, "BTCUSDT");
        manager.markReconciliationComplete();

        assertTrue(manager.openManualBuy(new BigDecimal("100000"), NOW).opened());

        assertEquals(0, new BigDecimal("500").compareTo(portfolio.getCurrentBalance()), "half the USDT was spent");
        assertEquals(0, new BigDecimal("1000").compareTo(portfolio.getEquity()));
        assertEquals(0, BigDecimal.ZERO.compareTo(portfolio.calculateDrawdownPercent()));
        assertTrue(portfolio.canTrade());
    }

    @Test
    public void unrealizedLossShowsUpOnTicks() {
        AccountExecutor executor = new AccountExecutor();
        PortfolioManager portfolio = new PortfolioManager(new BigDecimal("1000"), new BigDecimal("10"));
        PositionManager manager = new PositionManager(new BigDecimal("50"), new BigDecimal("50"));
        manager.setOrderIoExecutor(Runnable::run);
        manager.setRealTradingMode(executor, portfolio, "BTCUSDT");
        manager.markReconciliationComplete();
        assertTrue(manager.openManualBuy(new BigDecimal("100000"), NOW).opened());

        // 0.005 BTC losing 20%: equity 900, drawdown 10%
        manager.processPriceEvent(tick("80000"));

        assertTrue(manager.getOpenPosition().isPresent());
        assertEquals(0, new BigDecimal("900").compareTo(portfolio.getEquity()));
        assertFalse(portfolio.canTrade(), "a 10% equity drawdown must stop new entries");

        // Recovery below 80% of the limit re-enables trading
        manager.processPriceEvent(tick("99000"));
        assertTrue(portfolio.canTrade());
    }

    @Test
    public void baseHeldAtStartupIsPartOfTheInitialCapital() {
        PortfolioManager portfolio = new PortfolioManager(new BigDecimal("500"), new BigDecimal("5"));
        portfolio.setInitialBaseQuantity(new BigDecimal("0.005"));

        // No price yet: nothing to judge
        portfolio.updateBalance(new BigDecimal("500"));
        assertTrue(portfolio.canTrade());

        portfolio.updateMarketPrice(new BigDecimal("100000"));
        assertEquals(0, new BigDecimal("1000").compareTo(portfolio.getInitialCapital()));
        assertEquals(0, BigDecimal.ZERO.compareTo(portfolio.calculateDrawdownPercent()));

        portfolio.updateMarketPrice(new BigDecimal("80000"));
        assertEquals(0, new BigDecimal("10").compareTo(portfolio.calculateDrawdownPercent()));
        assertFalse(portfolio.canTrade());
    }

    @Test
    public void quoteOnlyAccountBehavesAsBefore() {
        PortfolioManager portfolio = new PortfolioManager(new BigDecimal("1000"), new BigDecimal("5"));

        portfolio.updateMarketPrice(new BigDecimal("100000"));
        assertTrue(portfolio.canTrade());

        portfolio.updateBalances(new BigDecimal("940"), BigDecimal.ZERO);
        assertFalse(portfolio.canTrade());
    }
}
