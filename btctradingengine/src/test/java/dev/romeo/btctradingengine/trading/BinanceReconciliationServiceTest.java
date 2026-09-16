package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance criteria of issue #64: with no open orders on Binance (MARKET orders never stay open),
 * reconciliation still recovers the persisted position's fill.
 */
public class BinanceReconciliationServiceTest {

    private static final String ENTRY_ID = "btce-BTCUSDT-POS_7-entry";

    /** Fakes Binance: no open orders, and the entry lookup and BTC balance answer what the test sets. */
    private static final class FakeExecutor extends BinanceOrderExecutor {
        Optional<QueriedOrder> entry = Optional.empty();
        BalanceResult btcBalance = new BalanceResult(true, new BigDecimal("1"), BigDecimal.ZERO, new BigDecimal("1"), null);
        final List<String> queried = new ArrayList<>();

        FakeExecutor() {
            super("test-key", "test-secret", "https://testnet.binance.vision", 10, 1000, 60000);
        }

        @Override
        public List<OpenOrder> getOpenOrders(String symbol) {
            return List.of();
        }

        @Override
        public Optional<QueriedOrder> queryOrder(String symbol, String clientOrderId) {
            queried.add(clientOrderId);
            return entry;
        }

        @Override
        public BalanceResult getBalance(String asset) {
            return btcBalance;
        }
    }

    private final FakeExecutor executor = new FakeExecutor();
    private final List<String> alerts = new ArrayList<>();
    private final PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
    private final BinanceReconciliationService reconciliation = new BinanceReconciliationService(
            executor, manager, new BigDecimal("2.0"), new BigDecimal("1.5"), alerts::add);

    @Test
    public void recoversTheFillOfAPersistedPositionWithoutOpenOrders() {
        // A row written before the quantity column existed restores with quantity 0
        Position persisted = persistedPosition(BigDecimal.ZERO);
        executor.entry = Optional.of(new BinanceOrderExecutor.QueriedOrder(
                99, ENTRY_ID, "FILLED", new BigDecimal("0.00500"), new BigDecimal("100000")));

        var result = reconciliation.reconcile("BTCUSDT");

        assertTrue(result.success());
        assertEquals("Persisted position reconciled", result.message());
        assertEquals(List.of(ENTRY_ID), executor.queried, "the entry must be looked up despite no open orders");
        assertEquals(0, persisted.getQuantity().compareTo(new BigDecimal("0.005")), "quantity=" + persisted.getQuantity());
        assertEquals(List.of(), alerts);
    }

    @Test
    public void keepsThePersistedQuantityWhenBinanceDoesNotAnswer() {
        Position persisted = persistedPosition(new BigDecimal("0.005"));

        var result = reconciliation.reconcile("BTCUSDT");

        assertTrue(result.success());
        assertEquals(0, persisted.getQuantity().compareTo(new BigDecimal("0.005")), "quantity=" + persisted.getQuantity());
    }

    @Test
    public void alertsWhenTheBtcBalanceIsBelowThePositionQuantity() {
        persistedPosition(new BigDecimal("0.005"));
        executor.btcBalance = new BinanceOrderExecutor.BalanceResult(
                true, new BigDecimal("0.001"), BigDecimal.ZERO, new BigDecimal("0.001"), null);

        reconciliation.reconcile("BTCUSDT");

        assertEquals(1, alerts.size(), alerts.toString());
        assertTrue(alerts.get(0).startsWith("BTC balance 0.001 is below position POS_7"), alerts.get(0));
    }

    @Test
    public void toleratesTheCommissionTakenFromTheBoughtBtc() {
        persistedPosition(new BigDecimal("0.005"));
        // 0.1% commission paid in BTC
        executor.btcBalance = new BinanceOrderExecutor.BalanceResult(
                true, new BigDecimal("0.004995"), BigDecimal.ZERO, new BigDecimal("0.004995"), null);

        reconciliation.reconcile("BTCUSDT");

        assertEquals(List.of(), alerts);
    }

    @Test
    public void noOpenOrdersAndNoPositionIsStillANoOp() {
        var result = reconciliation.reconcile("BTCUSDT");

        assertTrue(result.success());
        assertEquals("No open orders", result.message());
        assertEquals(List.of(), executor.queried);
    }

    private Position persistedPosition(BigDecimal quantity) {
        Position position = new Position("POS_7", Signal.BUY, new BigDecimal("100000"),
                Instant.parse("2026-09-15T10:00:00Z"), new BigDecimal("2.0"), new BigDecimal("1.5"));
        position.setQuantity(quantity);
        assertTrue(manager.restoreOpenPosition(position));
        return position;
    }
}
