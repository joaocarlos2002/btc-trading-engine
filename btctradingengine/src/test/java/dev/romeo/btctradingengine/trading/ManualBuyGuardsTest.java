package dev.romeo.btctradingengine.trading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #79: a manual BUY goes through the same entry guards as an automatic one. */
public class ManualBuyGuardsTest {

    private static final BigDecimal PRICE = new BigDecimal("100");
    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    private final List<ConnectivityGuard> guards = new ArrayList<>();

    private static final class RecordingExecutor extends BinanceOrderExecutor {
        private final List<String> orders = new ArrayList<>();
        private final boolean fail;

        RecordingExecutor(boolean fail) {
            super("test-key", "test-secret", "https://testnet.binance.vision", 10, 1000, 60000);
            this.fail = fail;
        }

        @Override
        public OrderResult executeBuyMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            orders.add("BUY " + quantity);
            return fail
                    ? new OrderResult(false, null, BigDecimal.ZERO, BigDecimal.ZERO, "rejected")
                    : new OrderResult(true, "1", quantity, PRICE, null);
        }

        @Override
        public OrderResult executeSellMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            orders.add("SELL " + quantity);
            return new OrderResult(true, "2", quantity, PRICE, null);
        }

        @Override
        public SymbolFilters getSymbolFilters(String symbol) {
            return new SymbolFilters(symbol, new BigDecimal("10"), new BigDecimal("0.00001"),
                    new BigDecimal("1000"), new BigDecimal("0.00001"));
        }

        @Override
        public BalanceResult getBalance(String asset) {
            return new BalanceResult(true, new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"), null);
        }
    }

    @AfterEach
    public void shutdownGuards() {
        guards.forEach(ConnectivityGuard::shutdown);
    }

    private static PositionManager realManager(RecordingExecutor executor, PortfolioManager portfolio) {
        PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
        manager.setOrderIoExecutor(Runnable::run);
        manager.setRealTradingMode(executor, portfolio, "BTCUSDT");
        return manager;
    }

    private static PortfolioManager portfolio() {
        return new PortfolioManager(new BigDecimal("1000"), new BigDecimal("5"));
    }

    @Test
    public void blockedUntilReconciliationCompletes() {
        RecordingExecutor executor = new RecordingExecutor(false);
        PositionManager manager = realManager(executor, portfolio());

        PositionManager.ManualBuyResult result = manager.openManualBuy(PRICE, NOW);

        assertFalse(result.opened());
        assertTrue(result.message().contains("reconciliation"), result.message());
        assertTrue(manager.getOpenPosition().isEmpty());
        assertEquals(List.of(), executor.orders, "no order may reach Binance");
    }

    @Test
    public void blockedByPortfolioStop() {
        RecordingExecutor executor = new RecordingExecutor(false);
        PortfolioManager portfolio = portfolio();
        portfolio.updateBalance(new BigDecimal("900"));
        PositionManager manager = realManager(executor, portfolio);
        manager.markReconciliationComplete();

        PositionManager.ManualBuyResult result = manager.openManualBuy(PRICE, NOW);

        assertFalse(result.opened());
        assertTrue(result.message().contains("Portfolio stop-loss"), result.message());
        assertEquals(List.of(), executor.orders);
    }

    @Test
    public void blockedByUnhealthyConnectivity() {
        RecordingExecutor executor = new RecordingExecutor(false);
        PositionManager manager = realManager(executor, portfolio());
        manager.markReconciliationComplete();
        ConnectivityGuard guard = new ConnectivityGuard(Duration.ofMinutes(5), false);
        guards.add(guard);
        guard.onMarketDataStatus("disconnected");
        manager.setConnectivityGuard(guard);

        PositionManager.ManualBuyResult result = manager.openManualBuy(PRICE, NOW);

        assertFalse(result.opened());
        assertTrue(result.message().contains("market data stream disconnected"), result.message());
        assertEquals(List.of(), executor.orders);
    }

    @Test
    public void blockedWhenAPositionIsOpen() {
        PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
        assertTrue(manager.openManualBuy(PRICE, NOW).opened());

        PositionManager.ManualBuyResult result = manager.openManualBuy(PRICE, NOW);

        assertFalse(result.opened());
        assertTrue(result.message().contains("already open"), result.message());
    }

    @Test
    public void opensWhenAllGuardsPass() {
        RecordingExecutor executor = new RecordingExecutor(false);
        PositionManager manager = realManager(executor, portfolio());
        manager.markReconciliationComplete();

        PositionManager.ManualBuyResult result = manager.openManualBuy(PRICE, NOW);

        assertTrue(result.opened(), result.message());
        assertTrue(manager.getOpenPosition().isPresent());
        assertEquals(1, executor.orders.size());
    }

    @Test
    public void reportsAFailedEntryOrder() {
        RecordingExecutor executor = new RecordingExecutor(true);
        PositionManager manager = realManager(executor, portfolio());
        manager.markReconciliationComplete();

        PositionManager.ManualBuyResult result = manager.openManualBuy(PRICE, NOW);

        assertFalse(result.opened());
        assertTrue(result.message().contains("ORDER_FAILED"), result.message());
        assertTrue(manager.getOpenPosition().isEmpty());
    }
}
