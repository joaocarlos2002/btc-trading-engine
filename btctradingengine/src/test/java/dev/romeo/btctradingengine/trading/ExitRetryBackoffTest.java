package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #68: a failed exit order is retried with exponential backoff, not on every tick. */
public class ExitRetryBackoffTest {

    private static final Instant START = Instant.parse("2026-09-08T10:00:00Z");

    private static final class FlakyExitExecutor extends BinanceOrderExecutor {
        int sellAttempts = 0;
        boolean sellFails = true;

        FlakyExitExecutor() {
            super("test-key", "test-secret");
        }

        @Override
        public OrderResult executeBuyMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            return new OrderResult(true, "1", quantity, new BigDecimal("100"), null);
        }

        @Override
        public OrderResult executeSellMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            sellAttempts++;
            return sellFails
                    ? new OrderResult(false, null, BigDecimal.ZERO, BigDecimal.ZERO, "insufficient balance")
                    : new OrderResult(true, "2", quantity, new BigDecimal("90"), null);
        }

        @Override
        public SymbolFilters getSymbolFilters(String symbol) {
            return new SymbolFilters(symbol, new BigDecimal("10"), new BigDecimal("0.00001"),
                    new BigDecimal("1000"), new BigDecimal("0.00001"));
        }

        // Binance does not know the rejected exit orders
        @Override
        public Optional<QueriedOrder> queryOrder(String symbol, String clientOrderId) {
            return Optional.empty();
        }

        @Override
        public BalanceResult getBalance(String asset) {
            return new BalanceResult(true, new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"), null);
        }
    }

    private final FlakyExitExecutor executor = new FlakyExitExecutor();
    private final List<String> alerts = new ArrayList<>();
    private Instant now = START;

    private PositionManager openRealBuy() {
        PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
        manager.setRealTradingMode(executor, new PortfolioManager(new BigDecimal("1000"), new BigDecimal("50")), "BTCUSDT");
        manager.markReconciliationComplete();
        manager.setAlertNotifier(alerts::add);
        manager.setClock(() -> now);
        assertTrue(manager.openManualBuy(new BigDecimal("100"), now).opened());
        return manager;
    }

    private void stopLossTick(PositionManager manager) {
        manager.processPriceEvent(new NormalizedPriceEvent("BTCUSDT", new BigDecimal("90"), now, now));
    }

    @Test
    public void hundredTicksAfterAFailureOnlyRetryPerBackoff() {
        PositionManager manager = openRealBuy();

        // 100 ticks over 10 seconds: attempts at 0s, 1s, 3s and 7s
        for (int i = 0; i < 100; i++) {
            stopLossTick(manager);
            now = now.plusMillis(100);
        }

        assertEquals(4, executor.sellAttempts);
        assertEquals(1, alerts.size(), "only the first failure alerts");
        assertTrue(manager.getOpenPosition().isPresent());
    }

    @Test
    public void alertsOnFirstAndEveryTenthFailure() {
        PositionManager manager = openRealBuy();

        for (int i = 0; i < 20; i++) {
            stopLossTick(manager);
            now = now.plusSeconds(61);
        }

        assertEquals(20, executor.sellAttempts);
        assertEquals(3, alerts.size(), "failures 1, 10 and 20");
    }

    @Test
    public void successfulRetryClosesAndResetsTheBackoff() {
        PositionManager manager = openRealBuy();
        stopLossTick(manager);
        stopLossTick(manager);
        assertEquals(1, executor.sellAttempts, "second tick waits for the backoff");

        executor.sellFails = false;
        now = now.plusSeconds(1);
        stopLossTick(manager);

        assertTrue(manager.getOpenPosition().isEmpty());
        assertEquals(ExitReason.STOP_LOSS, manager.getClosedPositions().getLast().getExitReason());

        // A new position starts with a clean counter: its first failure alerts again right away
        executor.sellFails = true;
        assertTrue(manager.openManualBuy(new BigDecimal("100"), now).opened());
        stopLossTick(manager);
        assertEquals(3, executor.sellAttempts);
        assertEquals(2, alerts.size());
    }

    @Test
    public void manualCloseSkipsTheWait() {
        PositionManager manager = openRealBuy();
        stopLossTick(manager);
        executor.sellFails = false;

        assertTrue(manager.closeManualPosition(new BigDecimal("90"), now));
        assertEquals(2, executor.sellAttempts);
    }

    @Test
    public void delayDoublesUpToSixtySeconds() {
        assertEquals(Duration.ofSeconds(1), PositionManager.exitRetryDelay(1));
        assertEquals(Duration.ofSeconds(2), PositionManager.exitRetryDelay(2));
        assertEquals(Duration.ofSeconds(32), PositionManager.exitRetryDelay(6));
        assertEquals(Duration.ofSeconds(60), PositionManager.exitRetryDelay(7));
        assertEquals(Duration.ofSeconds(60), PositionManager.exitRetryDelay(1000));
    }
}
