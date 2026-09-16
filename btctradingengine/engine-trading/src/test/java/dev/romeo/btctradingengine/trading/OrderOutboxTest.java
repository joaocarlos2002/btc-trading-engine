package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #111: every order goes through the outbox, and startup resolves leftovers without resending. */
public class OrderOutboxTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final String ENTRY_ID = "btce-BTCUSDT-POS_1-entry";
    private static final String EXIT_ID = "btce-BTCUSDT-POS_1-exit";
    private static final String LIST_ID = "btce-BTCUSDT-POS_1-oco";

    private static final class Exchange extends BinanceOrderExecutor {
        final List<String> orders = new ArrayList<>();
        final Map<String, OrderLookup> lookups = new HashMap<>();
        OrderListQuery listAnswer = new OrderListQuery(OrderListQuery.State.FOUND, "EXECUTING", null);
        boolean buyFails;

        Exchange() {
            super("test-key", "test-secret", "https://testnet.binance.vision", 10, 1000, 60000);
        }

        @Override
        public OrderResult executeBuyMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            orders.add("buy " + clientOrderId);
            return buyFails
                    ? new OrderResult(false, null, BigDecimal.ZERO, BigDecimal.ZERO, "rejected")
                    : new OrderResult(true, "1", new BigDecimal("0.005"), new BigDecimal("100000"), null);
        }

        @Override
        public OrderResult executeSellMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            orders.add("sell " + clientOrderId);
            return new OrderResult(true, "2", quantity, new BigDecimal("99000"), null);
        }

        @Override
        public OcoResult placeOcoSell(String symbol, BigDecimal quantity, BigDecimal targetPrice, BigDecimal stopPrice,
                                      BigDecimal stopLimitPrice, String listClientOrderId,
                                      String targetClientOrderId, String stopClientOrderId) {
            orders.add("oco " + listClientOrderId);
            return new OcoResult(true, 7, "EXECUTING", null);
        }

        @Override
        public OrderListQuery cancelOrderList(String symbol, String listClientOrderId) {
            orders.add("cancel " + listClientOrderId);
            return new OrderListQuery(OrderListQuery.State.FOUND, "ALL_DONE", null);
        }

        @Override
        public OrderListQuery queryOrderList(String listClientOrderId) {
            return listAnswer;
        }

        @Override
        public OrderLookup lookupOrder(String symbol, String clientOrderId) {
            return lookups.getOrDefault(clientOrderId, OrderLookup.error("timeout"));
        }

        @Override
        public Optional<QueriedOrder> queryOrder(String symbol, String clientOrderId) {
            return Optional.empty();
        }

        @Override
        public List<OpenOrder> getOpenOrders(String symbol) {
            return List.of();
        }

        @Override
        public SymbolFilters getSymbolFilters(String symbol) {
            return new SymbolFilters(symbol, new BigDecimal("5"), new BigDecimal("0.00001"),
                    new BigDecimal("1000"), new BigDecimal("0.00001"), new BigDecimal("0.01"));
        }

        @Override
        public BalanceResult getBalance(String asset) {
            BigDecimal amount = "BTC".equals(asset) ? new BigDecimal("0.005") : new BigDecimal("1000");
            return new BalanceResult(true, amount, BigDecimal.ZERO, amount, null);
        }
    }

    private final Exchange exchange = new Exchange();
    private final InMemoryOrderCommandStore store = new InMemoryOrderCommandStore(() -> NOW);
    private final List<String> alerts = new ArrayList<>();

    private PositionManager manager(boolean oco) {
        PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
        manager.setOrderIoExecutor(Runnable::run);
        manager.setRealTradingMode(exchange, new PortfolioManager(new BigDecimal("1000"), new BigDecimal("50")), "BTCUSDT");
        manager.setOcoProtection(oco, new BigDecimal("0.1"));
        manager.setOrderCommandStore(store);
        manager.setAlertNotifier(alerts::add);
        manager.markReconciliationComplete();
        return manager;
    }

    private OrderCommand command(String clientOrderId) {
        return store.find(clientOrderId).orElseThrow();
    }

    @Test
    public void entryAndExitAreRecordedAndConfirmed() {
        PositionManager manager = manager(false);
        manager.openManualBuy(new BigDecimal("100000"), NOW);
        manager.closeManualPosition(new BigDecimal("99000"), NOW);

        OrderCommand entry = command(ENTRY_ID);
        assertEquals(OrderCommand.Type.ENTRY, entry.type());
        assertEquals(OrderCommand.Status.CONFIRMED, entry.status());
        assertEquals("BUY", entry.payload().get("side"));
        OrderCommand exit = command(EXIT_ID);
        assertEquals(OrderCommand.Status.CONFIRMED, exit.status());
        assertEquals("MANUAL_CLOSE", exit.payload().get("reason"));
        assertEquals("SELL", exit.payload().get("side"));
        assertEquals(List.of(), store.findUnresolved());
    }

    @Test
    public void rejectedEntryIsRecordedAsFailed() {
        exchange.buyFails = true;
        manager(false).openManualBuy(new BigDecimal("100000"), NOW);

        assertEquals(OrderCommand.Status.FAILED, command(ENTRY_ID).status());
        assertEquals("rejected", command(ENTRY_ID).lastError());
    }

    @Test
    public void ocoAndItsCancelAreRecorded() {
        PositionManager manager = manager(true);
        manager.openManualBuy(new BigDecimal("100000"), NOW);
        manager.closeManualPosition(new BigDecimal("99000"), NOW);

        assertEquals(OrderCommand.Status.CONFIRMED, command(LIST_ID).status());
        assertEquals(OrderCommand.Type.CANCEL, command(OrderCommand.cancelKey(LIST_ID)).type());
        assertEquals(OrderCommand.Status.CONFIRMED, command(OrderCommand.cancelKey(LIST_ID)).status());
        assertEquals(List.of("buy " + ENTRY_ID, "oco " + LIST_ID, "cancel " + LIST_ID, "sell " + EXIT_ID), exchange.orders);
    }

    @Test
    public void recordingAgainResetsTheSameRow() {
        store.record(EXIT_ID, "POS_1", OrderCommand.Type.EXIT, Map.of());
        store.markFailed(EXIT_ID, "rejected");
        store.record(EXIT_ID, "POS_1", OrderCommand.Type.EXIT, Map.of());

        OrderCommand exit = command(EXIT_ID);
        assertEquals(OrderCommand.Status.PENDING, exit.status());
        assertEquals(2, exit.attempts());
        assertEquals(1, store.all().size());
    }

    // ---- startup reconciliation ----

    private PositionManager restarted(BigDecimal quantity) {
        PositionManager manager = manager(false);
        Position persisted = new Position("POS_1", Signal.BUY, new BigDecimal("100000"), NOW,
                new BigDecimal("2.0"), new BigDecimal("1.5"));
        persisted.setQuantity(quantity);
        manager.restoreOpenPosition(persisted);
        return manager;
    }

    private BinanceReconciliationService reconciliation(PositionManager manager) {
        BinanceReconciliationService service = new BinanceReconciliationService(exchange, manager,
                new BigDecimal("2.0"), new BigDecimal("1.5"), alerts::add);
        service.setOrderCommandStore(store);
        return service;
    }

    @Test
    public void sentExitFilledWhileDownClosesThePositionWithoutResending() {
        PositionManager manager = restarted(new BigDecimal("0.005"));
        store.record(EXIT_ID, "POS_1", OrderCommand.Type.EXIT, Map.of("reason", "STOP_LOSS"));
        store.markSent(EXIT_ID);
        exchange.lookups.put(EXIT_ID, BinanceOrderExecutor.OrderLookup.found(new BinanceOrderExecutor.QueriedOrder(
                5, EXIT_ID, "FILLED", new BigDecimal("0.005"), new BigDecimal("98400"))));

        var result = reconciliation(manager).reconcile("BTCUSDT");

        assertTrue(result.success());
        assertEquals(OrderCommand.Status.CONFIRMED, command(EXIT_ID).status());
        assertTrue(manager.getOpenPosition().isEmpty());
        Position closed = manager.getClosedPositions().getLast();
        assertEquals(ExitReason.STOP_LOSS, closed.getExitReason());
        assertEquals(0, new BigDecimal("98400").compareTo(closed.getExitPrice()));
        assertEquals(List.of(), exchange.orders, "reconciliation never sends an order");
    }

    @Test
    public void pendingEntryUnknownToBinanceFailsThePosition() {
        PositionManager manager = restarted(BigDecimal.ZERO);
        store.record(ENTRY_ID, "POS_1", OrderCommand.Type.ENTRY, Map.of());
        exchange.lookups.put(ENTRY_ID, BinanceOrderExecutor.OrderLookup.notFound());

        reconciliation(manager).reconcile("BTCUSDT");

        assertEquals(OrderCommand.Status.FAILED, command(ENTRY_ID).status());
        assertTrue(manager.getOpenPosition().isEmpty());
        assertEquals(PositionState.FAILED, manager.getClosedPositions().getLast().getState());
        assertEquals(List.of(), exchange.orders);
    }

    @Test
    public void unansweredLookupStaysUnresolvedAndAlerts() {
        PositionManager manager = restarted(new BigDecimal("0.005"));
        store.record(EXIT_ID, "POS_1", OrderCommand.Type.EXIT, Map.of());
        store.markSent(EXIT_ID);

        reconciliation(manager).reconcile("BTCUSDT");

        assertEquals(OrderCommand.Status.SENT, command(EXIT_ID).status());
        assertTrue(manager.getOpenPosition().isPresent());
        assertTrue(alerts.stream().anyMatch(a -> a.contains("still unresolved")), alerts.toString());
        assertEquals(List.of(), exchange.orders);
    }

    @Test
    public void ocoCommandsResolveByListId() {
        store.record(LIST_ID, "POS_1", OrderCommand.Type.OCO, Map.of());
        store.record(OrderCommand.cancelKey(LIST_ID), "POS_1", OrderCommand.Type.CANCEL, Map.of());
        exchange.listAnswer = new BinanceOrderExecutor.OrderListQuery(
                BinanceOrderExecutor.OrderListQuery.State.FOUND, "ALL_DONE", null);

        var resolutions = new OrderCommandReconciler(exchange, store).resolve("BTCUSDT");

        assertEquals(2, resolutions.size());
        assertEquals(OrderCommand.Status.CONFIRMED, command(LIST_ID).status());
        assertEquals(OrderCommand.Status.CONFIRMED, command(OrderCommand.cancelKey(LIST_ID)).status());
    }

    @Test
    public void entryAndExitIdsFitBinanceLimit() {
        String entry = OcoOrderIds.entry("BTCUSDT", "BINANCE_123456789012");
        String exit = OcoOrderIds.exit("BTCUSDT", "BINANCE_123456789012");
        assertTrue(entry.length() <= OcoOrderIds.MAX_CLIENT_ORDER_ID_LENGTH, entry);
        assertTrue(exit.length() <= OcoOrderIds.MAX_CLIENT_ORDER_ID_LENGTH, exit);
        assertEquals(ENTRY_ID, OcoOrderIds.entry("BTCUSDT", "POS_1"));
        assertEquals(EXIT_ID, OcoOrderIds.exit("BTCUSDT", "POS_1"));
    }
}
