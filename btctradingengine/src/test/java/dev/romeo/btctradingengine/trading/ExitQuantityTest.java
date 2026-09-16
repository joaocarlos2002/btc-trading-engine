package dev.romeo.btctradingengine.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #69: the exit of a BUY never sells more than the free base balance. */
public class ExitQuantityTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    private static final class FakeExecutor extends BinanceOrderExecutor {
        final List<String> sells = new ArrayList<>();
        final List<String> sellClientIds = new ArrayList<>();
        BalanceResult baseBalance;
        SymbolFilters filters = new SymbolFilters("BTCUSDT", new BigDecimal("5"), new BigDecimal("0.00001"),
                new BigDecimal("1000"), new BigDecimal("0.00001"));
        BigDecimal buyFill = new BigDecimal("0.00050");
        OrderResult sellAnswer;
        Optional<QueriedOrder> queryAnswer = Optional.empty();

        FakeExecutor() {
            super("test-key", "test-secret");
        }

        @Override
        public OrderResult executeBuyMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            return new OrderResult(true, "1", buyFill, new BigDecimal("100000"), null);
        }

        @Override
        public OrderResult executeSellMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            sells.add(quantity.toPlainString());
            sellClientIds.add(clientOrderId);
            return sellAnswer != null ? sellAnswer : new OrderResult(true, "2", quantity, new BigDecimal("100000"), null);
        }

        @Override
        public SymbolFilters getSymbolFilters(String symbol) {
            return filters;
        }

        @Override
        public BalanceResult getBalance(String asset) {
            if ("BTC".equals(asset)) {
                return baseBalance;
            }
            return new BalanceResult(true, new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"), null);
        }

        @Override
        public Optional<QueriedOrder> queryOrder(String symbol, String clientOrderId) {
            return queryAnswer;
        }
    }

    private static BinanceOrderExecutor.BalanceResult free(String qty) {
        return new BinanceOrderExecutor.BalanceResult(true, new BigDecimal(qty), BigDecimal.ZERO, new BigDecimal(qty), null);
    }

    private static PositionManager openBuy(FakeExecutor executor) {
        PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
        manager.setRealTradingMode(executor, new PortfolioManager(new BigDecimal("1000"), new BigDecimal("50")), "BTCUSDT");
        manager.markReconciliationComplete();
        assertTrue(manager.openManualBuy(new BigDecimal("100000"), NOW).opened());
        return manager;
    }

    @Test
    public void sellsTheFreeBalanceWhenTheFeeWasTakenInBtc() {
        FakeExecutor executor = new FakeExecutor();
        PositionManager manager = openBuy(executor);
        // 0.1% fee taken from 0.0005 BTC, rounded down to the 0.00001 step
        executor.baseBalance = free("0.0004995");

        assertTrue(manager.closeManualPosition(new BigDecimal("100000"), NOW));

        assertEquals(List.of("0.00049"), executor.sells);
        assertEquals(List.of("btce-BTCUSDT-POS_1-exit"), executor.sellClientIds);
    }

    @Test
    public void sellsThePositionQuantityWhenTheBalanceCoversIt() {
        FakeExecutor executor = new FakeExecutor();
        PositionManager manager = openBuy(executor);
        executor.baseBalance = free("0.01");

        assertTrue(manager.closeManualPosition(new BigDecimal("100000"), NOW));

        assertEquals(List.of("0.0005"), executor.sells);
    }

    @Test
    public void fallsBackToThePositionQuantityWhenTheBalanceFetchFails() {
        FakeExecutor executor = new FakeExecutor();
        executor.buyFill = new BigDecimal("0.000505");
        PositionManager manager = openBuy(executor);
        executor.baseBalance = new BinanceOrderExecutor.BalanceResult(false, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, "timeout");

        assertTrue(manager.closeManualPosition(new BigDecimal("100000"), NOW));

        assertEquals(List.of("0.0005"), executor.sells, "position qty rounded down to the step");
    }

    @Test
    public void doesNotRoundWhenTheFiltersAreUnavailable() {
        FakeExecutor executor = new FakeExecutor();
        PositionManager manager = openBuy(executor);
        executor.baseBalance = free("0.0004995");
        executor.filters = null;

        assertTrue(manager.closeManualPosition(new BigDecimal("100000"), NOW));

        assertEquals(List.of("0.0004995"), executor.sells);
    }

    @Test
    public void noFreeBalanceSendsNoOrder() {
        FakeExecutor executor = new FakeExecutor();
        PositionManager manager = openBuy(executor);
        executor.baseBalance = free("0");

        assertEquals(false, manager.closeManualPosition(new BigDecimal("100000"), NOW));

        assertEquals(List.of(), executor.sells);
        assertTrue(manager.getOpenPosition().isPresent());
    }

    @Test
    public void ambiguousErrorIsRecoveredByClientOrderId() {
        FakeExecutor executor = new FakeExecutor();
        PositionManager manager = openBuy(executor);
        executor.baseBalance = free("0.0005");
        executor.sellAnswer = new BinanceOrderExecutor.OrderResult(false, null, BigDecimal.ZERO, BigDecimal.ZERO,
                "request timed out");
        executor.queryAnswer = Optional.of(new BinanceOrderExecutor.QueriedOrder(
                99L, "btce-BTCUSDT-POS_1-exit", "FILLED", new BigDecimal("0.0005"), new BigDecimal("101000")));

        assertTrue(manager.closeManualPosition(new BigDecimal("100000"), NOW));

        assertTrue(manager.getOpenPosition().isEmpty());
        assertEquals(0, new BigDecimal("101000").compareTo(manager.getClosedPositions().getLast().getExitPrice()));
    }
}
