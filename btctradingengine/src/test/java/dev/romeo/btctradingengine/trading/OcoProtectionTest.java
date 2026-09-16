package dev.romeo.btctradingengine.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.prediction.PredictionVector;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #99: target and stop rest on Binance as an OCO while a real BUY is open. */
public class OcoProtectionTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final String LIST_ID = "btce-BTCUSDT-POS_1-oco";
    private static final String TP_ID = "btce-BTCUSDT-POS_1-tp";
    private static final String SL_ID = "btce-BTCUSDT-POS_1-sl";

    static class FakeExecutor extends BinanceOrderExecutor {
        final List<String> calls = new ArrayList<>();
        BigDecimal entryPrice = new BigDecimal("100000.37");
        BalanceResult baseBalance = new BalanceResult(true, new BigDecimal("0.0004995"), BigDecimal.ZERO,
                new BigDecimal("0.0004995"), null);
        SymbolFilters filters = new SymbolFilters("BTCUSDT", new BigDecimal("5"), new BigDecimal("0.00001"),
                new BigDecimal("1000"), new BigDecimal("0.00001"), new BigDecimal("0.01"));
        OcoResult ocoAnswer = new OcoResult(true, 7, "EXECUTING", null);
        OrderListQuery listAnswer = new OrderListQuery(OrderListQuery.State.FOUND, "EXECUTING", null);
        OrderListQuery cancelAnswer = new OrderListQuery(OrderListQuery.State.FOUND, "ALL_DONE", null);
        final Map<String, QueriedOrder> orders = new HashMap<>();
        OcoArgs lastOco;

        record OcoArgs(BigDecimal quantity, BigDecimal target, BigDecimal stop, BigDecimal stopLimit,
                       String listId, String targetId, String stopId) {}

        FakeExecutor() {
            super("test-key", "test-secret");
        }

        @Override
        public OrderResult executeBuyMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            calls.add("buy");
            return new OrderResult(true, "1", new BigDecimal("0.0005"), entryPrice, null);
        }

        @Override
        public OrderResult executeSellMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            calls.add("sell " + quantity.toPlainString());
            return new OrderResult(true, "2", quantity, new BigDecimal("99000"), null);
        }

        @Override
        public OcoResult placeOcoSell(String symbol, BigDecimal quantity, BigDecimal targetPrice, BigDecimal stopPrice,
                                      BigDecimal stopLimitPrice, String listClientOrderId,
                                      String targetClientOrderId, String stopClientOrderId) {
            calls.add("oco");
            lastOco = new OcoArgs(quantity, targetPrice, stopPrice, stopLimitPrice,
                    listClientOrderId, targetClientOrderId, stopClientOrderId);
            return ocoAnswer;
        }

        @Override
        public OrderListQuery queryOrderList(String listClientOrderId) {
            calls.add("query " + listClientOrderId);
            return listAnswer;
        }

        @Override
        public OrderListQuery cancelOrderList(String symbol, String listClientOrderId) {
            calls.add("cancel " + listClientOrderId);
            return cancelAnswer;
        }

        @Override
        public Optional<QueriedOrder> queryOrder(String symbol, String clientOrderId) {
            return Optional.ofNullable(orders.get(clientOrderId));
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
        public List<OpenOrder> getOpenOrders(String symbol) {
            return List.of();
        }

        void legs(String tpStatus, String slStatus, BigDecimal slQty, BigDecimal slPrice) {
            orders.put(TP_ID, new QueriedOrder(10, TP_ID, tpStatus, BigDecimal.ZERO, BigDecimal.ZERO));
            orders.put(SL_ID, new QueriedOrder(11, SL_ID, slStatus, slQty, slPrice));
        }
    }

    private final FakeExecutor executor = new FakeExecutor();
    private final List<String> alerts = new ArrayList<>();
    private Instant now = NOW;

    private PositionManager realManager() {
        PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
        manager.setRealTradingMode(executor, new PortfolioManager(new BigDecimal("1000"), new BigDecimal("50")), "BTCUSDT");
        manager.setOcoProtection(true, new BigDecimal("0.1"));
        manager.setAlertNotifier(alerts::add);
        manager.setClock(() -> now);
        manager.markReconciliationComplete();
        return manager;
    }

    private PositionManager openBuy() {
        PositionManager manager = realManager();
        assertTrue(manager.openManualBuy(new BigDecimal("100000"), NOW).opened());
        return manager;
    }

    private void tick(PositionManager manager, String price) {
        manager.processPriceEvent(new NormalizedPriceEvent("BTCUSDT", new BigDecimal(price), now, now));
    }

    private static boolean same(String expected, BigDecimal actual) {
        return new BigDecimal(expected).compareTo(actual) == 0;
    }

    @Test
    public void placesOcoAfterEntryWithTickAndStepCorrectPrices() {
        PositionManager manager = openBuy();

        assertTrue(manager.isProtectionActive());
        FakeExecutor.OcoArgs oco = executor.lastOco;
        // 100000.37 * 1.02 = 102000.3774 -> up to 102000.38; * 0.985 = 98500.36445 -> down to 98500.36
        assertTrue(same("102000.38", oco.target()), "target=" + oco.target());
        assertTrue(same("98500.36", oco.stop()), "stop=" + oco.stop());
        // 98500.36 * 0.999 = 98401.85964 -> 98401.85
        assertTrue(same("98401.85", oco.stopLimit()), "limit=" + oco.stopLimit());
        // Free balance after a BTC fee, rounded down to the step
        assertTrue(same("0.00049", oco.quantity()), "qty=" + oco.quantity());
        assertEquals(LIST_ID, oco.listId());
        assertEquals(TP_ID, oco.targetId());
        assertEquals(SL_ID, oco.stopId());
        assertEquals(List.of(), alerts);
    }

    @Test
    public void tickTargetAndStopDoNotSendMarketOrdersWhileOcoActive() {
        PositionManager manager = openBuy();

        tick(manager, "103000");
        now = now.plusSeconds(30);
        tick(manager, "98450");   // below the stop, above its limit

        assertEquals(List.of("buy", "oco", "query " + LIST_ID, "query " + LIST_ID), executor.calls);
        assertTrue(manager.getOpenPosition().isPresent());
        assertTrue(manager.isProtectionActive());
    }

    @Test
    public void candleTargetDoesNotSendMarketOrderWhileOcoActive() {
        PositionManager manager = openBuy();

        manager.processPrediction(prediction(Signal.HOLD), candle("103000"));

        assertFalse(executor.calls.stream().anyMatch(call -> call.startsWith("sell")), executor.calls.toString());
        assertTrue(manager.getOpenPosition().isPresent());
    }

    @Test
    public void pollsAtMostOncePerIntervalWhileBeyondTheLevels() {
        PositionManager manager = openBuy();

        tick(manager, "103000");
        tick(manager, "103001");

        assertEquals(1, executor.calls.stream().filter(call -> call.startsWith("query")).count());
    }

    @Test
    public void reversalCancelsOcoThenSells() {
        PositionManager manager = openBuy();

        manager.processPrediction(prediction(Signal.SELL), candle("100500"));

        assertEquals(List.of("buy", "oco", "cancel " + LIST_ID, "sell 0.00049"), executor.calls);
        assertTrue(manager.getOpenPosition().isEmpty());
        assertEquals(ExitReason.SIGNAL_REVERSAL, manager.getClosedPositions().getLast().getExitReason());
        assertFalse(manager.isProtectionActive());
    }

    @Test
    public void cancelFailingBecauseOcoFilledClosesWithoutSelling() {
        PositionManager manager = openBuy();
        executor.cancelAnswer = new BinanceOrderExecutor.OrderListQuery(
                BinanceOrderExecutor.OrderListQuery.State.NOT_FOUND, "", "{\"code\":-2011}");
        executor.listAnswer = new BinanceOrderExecutor.OrderListQuery(
                BinanceOrderExecutor.OrderListQuery.State.FOUND, "ALL_DONE", null);
        executor.legs("EXPIRED", "FILLED", new BigDecimal("0.00049"), new BigDecimal("98400.10"));

        assertTrue(manager.closeManualPosition(new BigDecimal("98000"), NOW));

        assertFalse(executor.calls.stream().anyMatch(call -> call.startsWith("sell")), executor.calls.toString());
        Position closed = manager.getClosedPositions().getLast();
        assertEquals(ExitReason.STOP_LOSS, closed.getExitReason());
        assertTrue(same("98400.10", closed.getExitPrice()));
    }

    @Test
    public void cancelErrorKeepsThePositionAndSendsNoSell() {
        PositionManager manager = openBuy();
        executor.cancelAnswer = new BinanceOrderExecutor.OrderListQuery(
                BinanceOrderExecutor.OrderListQuery.State.ERROR, "", "timeout");

        assertFalse(manager.closeManualPosition(new BigDecimal("98000"), NOW));

        assertFalse(executor.calls.stream().anyMatch(call -> call.startsWith("sell")));
        assertTrue(manager.getOpenPosition().isPresent());
        assertTrue(manager.isProtectionActive());
    }

    @Test
    public void legFillReportClosesPositionWithReasonAndAveragePrice() throws Exception {
        PositionManager manager = realManager();
        OrderConfirmationManager confirmations = new OrderConfirmationManager(executor, false);
        manager.setOrderConfirmationManager(confirmations);
        assertTrue(manager.openManualBuy(new BigDecimal("100000"), NOW).opened());
        executor.orders.put(TP_ID, new BinanceOrderExecutor.QueriedOrder(
                10, TP_ID, "FILLED", new BigDecimal("0.00049"), new BigDecimal("102000.38")));

        confirmations.processExecutionReport(report(TP_ID, "FILLED", "0.00049", "102000.38"));

        assertTrue(manager.getOpenPosition().isEmpty());
        Position closed = manager.getClosedPositions().getLast();
        assertEquals(ExitReason.TARGET_HIT, closed.getExitReason());
        assertTrue(same("102000.38", closed.getExitPrice()));
        assertFalse(executor.calls.stream().anyMatch(call -> call.startsWith("sell")));
        assertFalse(confirmations.isPending(SL_ID));
    }

    @Test
    public void expiredLegReportBeforeTheFillStillClosesByTheFilledLeg() throws Exception {
        PositionManager manager = realManager();
        OrderConfirmationManager confirmations = new OrderConfirmationManager(executor, false);
        manager.setOrderConfirmationManager(confirmations);
        assertTrue(manager.openManualBuy(new BigDecimal("100000"), NOW).opened());
        executor.listAnswer = new BinanceOrderExecutor.OrderListQuery(
                BinanceOrderExecutor.OrderListQuery.State.FOUND, "ALL_DONE", null);
        executor.legs("EXPIRED", "FILLED", new BigDecimal("0.00049"), new BigDecimal("98401.85"));

        confirmations.processExecutionReport(report(TP_ID, "EXPIRED", "0", "0"));

        assertEquals(ExitReason.STOP_LOSS, manager.getClosedPositions().getLast().getExitReason());
    }

    @Test
    public void placementFailureAlertsAndFallsBackToTickExits() {
        executor.ocoAnswer = new BinanceOrderExecutor.OcoResult(false, -1, "", "{\"code\":-2010}");
        executor.listAnswer = new BinanceOrderExecutor.OrderListQuery(
                BinanceOrderExecutor.OrderListQuery.State.NOT_FOUND, "", null);
        PositionManager manager = openBuy();

        assertFalse(manager.isProtectionActive());
        assertEquals(1, alerts.size());

        tick(manager, "103000");

        assertTrue(manager.getOpenPosition().isEmpty());
        assertTrue(executor.calls.contains("sell 0.00049"), executor.calls.toString());
        assertFalse(executor.calls.stream().anyMatch(call -> call.startsWith("cancel")));
        assertEquals(ExitReason.TARGET_HIT, manager.getClosedPositions().getLast().getExitReason());
    }

    @Test
    public void ambiguousPlacementErrorIsResolvedByTheListId() {
        executor.ocoAnswer = new BinanceOrderExecutor.OcoResult(false, -1, "", "request timed out");
        PositionManager manager = openBuy();

        assertTrue(manager.isProtectionActive());
        assertEquals(List.of(), alerts);
    }

    @Test
    public void priceBelowTheStopLimitWithoutFillCancelsAndExitsAtMarket() {
        PositionManager manager = openBuy();

        tick(manager, "98000");

        assertEquals(List.of("buy", "oco", "query " + LIST_ID, "cancel " + LIST_ID, "sell 0.00049"), executor.calls);
        assertEquals(ExitReason.STOP_LOSS, manager.getClosedPositions().getLast().getExitReason());
    }

    @Test
    public void disabledFlagPlacesNoOco() {
        PositionManager manager = realManager();
        manager.setOcoProtection(false, new BigDecimal("0.1"));
        assertTrue(manager.openManualBuy(new BigDecimal("100000"), NOW).opened());

        assertEquals(List.of("buy"), executor.calls);
        assertFalse(manager.isProtectionActive());
    }

    // ---- reconciliation after a restart ----

    private PositionManager restartedWithPosition() {
        PositionManager manager = realManager();
        Position persisted = new Position("POS_1", Signal.BUY, new BigDecimal("100000.37"), NOW,
                new BigDecimal("2.0"), new BigDecimal("1.5"));
        persisted.setQuantity(new BigDecimal("0.0005"));
        manager.restoreOpenPosition(persisted);
        return manager;
    }

    private BinanceReconciliationService reconciliation(PositionManager manager) {
        return new BinanceReconciliationService(executor, manager, new BigDecimal("2.0"), new BigDecimal("1.5"), alerts::add);
    }

    @Test
    public void reconciliationKeepsAnActiveOco() {
        PositionManager manager = restartedWithPosition();

        var result = reconciliation(manager).reconcile("BTCUSDT");

        assertTrue(result.success());
        assertTrue(manager.isProtectionActive());
        assertFalse(executor.calls.contains("oco"));
        assertTrue(manager.getOpenPosition().isPresent());
    }

    @Test
    public void reconciliationClosesAPositionWhoseOcoExecuted() {
        PositionManager manager = restartedWithPosition();
        executor.listAnswer = new BinanceOrderExecutor.OrderListQuery(
                BinanceOrderExecutor.OrderListQuery.State.FOUND, "ALL_DONE", null);
        executor.legs("EXPIRED", "FILLED", new BigDecimal("0.0005"), new BigDecimal("98400"));

        var result = reconciliation(manager).reconcile("BTCUSDT");

        assertTrue(result.success());
        assertEquals("Persisted position closed by its OCO", result.message());
        assertTrue(manager.getOpenPosition().isEmpty());
        assertEquals(ExitReason.STOP_LOSS, manager.getClosedPositions().getLast().getExitReason());
        assertFalse(executor.calls.stream().anyMatch(call -> call.startsWith("sell")));
    }

    @Test
    public void reconciliationReplacesAMissingOco() {
        PositionManager manager = restartedWithPosition();
        executor.listAnswer = new BinanceOrderExecutor.OrderListQuery(
                BinanceOrderExecutor.OrderListQuery.State.NOT_FOUND, "", null);

        reconciliation(manager).reconcile("BTCUSDT");

        assertTrue(executor.calls.contains("oco"));
        assertTrue(manager.isProtectionActive());
        assertEquals(LIST_ID, executor.lastOco.listId());
    }

    @Test
    public void reconciliationDoesNotTreatOcoLegsAsEntries() {
        PositionManager manager = restartedWithPosition();
        FakeExecutor withLegs = new FakeExecutor() {
            @Override
            public List<OpenOrder> getOpenOrders(String symbol) {
                return List.of(
                        new OpenOrder("10", "BTCUSDT", "SELL", new BigDecimal("0.0005"), BigDecimal.ZERO,
                                new BigDecimal("102000.38"), "NEW", 1L, TP_ID),
                        new OpenOrder("11", "BTCUSDT", "SELL", new BigDecimal("0.0005"), BigDecimal.ZERO,
                                new BigDecimal("98401.85"), "NEW", 1L, SL_ID));
            }

            @Override
            public boolean cancelOrder(String symbol, String orderId) {
                calls.add("cancelOrder " + orderId);
                return true;
            }
        };
        manager.setRealTradingMode(withLegs, new PortfolioManager(new BigDecimal("1000"), new BigDecimal("50")), "BTCUSDT");
        manager.setOcoProtection(true, new BigDecimal("0.1"));
        manager.markReconciliationComplete();

        var result = new BinanceReconciliationService(withLegs, manager, new BigDecimal("2.0"), new BigDecimal("1.5"),
                alerts::add).reconcile("BTCUSDT");

        assertEquals("Persisted position reconciled", result.message());
        assertEquals("POS_1", manager.getOpenPosition().orElseThrow().getPositionId());
        assertTrue(manager.isProtectionActive());
        assertFalse(withLegs.calls.stream().anyMatch(call -> call.startsWith("cancel")), withLegs.calls.toString());
    }

    @Test
    public void reconciliationCancelsAnOrphanOco() {
        PositionManager manager = realManager();
        FakeExecutor withOrphan = new FakeExecutor() {
            @Override
            public List<OpenOrder> getOpenOrders(String symbol) {
                return List.of(new OpenOrder("10", "BTCUSDT", "SELL", new BigDecimal("0.0005"), BigDecimal.ZERO,
                        new BigDecimal("102000.38"), "NEW", 1L, "btce-BTCUSDT-POS_3-tp"));
            }
        };

        var result = new BinanceReconciliationService(withOrphan, manager, new BigDecimal("2.0"), new BigDecimal("1.5"),
                alerts::add).reconcile("BTCUSDT");

        assertTrue(result.success());
        assertTrue(manager.getOpenPosition().isEmpty());
        assertEquals(List.of("cancel btce-BTCUSDT-POS_3-oco"), withOrphan.calls);
    }

    @Test
    public void idsFitBinanceLimitAndStayDeterministic() {
        assertEquals(LIST_ID, OcoOrderIds.list("BTCUSDT", "POS_1"));
        String longList = OcoOrderIds.list("BTCUSDT", "BINANCE_123456789012");
        assertTrue(longList.length() <= OcoOrderIds.MAX_CLIENT_ORDER_ID_LENGTH, longList);
        assertEquals(longList, OcoOrderIds.list("BTCUSDT", "BINANCE_123456789012"));
        String longStop = OcoOrderIds.stop("BTCUSDT", "BINANCE_123456789012");
        assertTrue(OcoOrderIds.isLeg(longStop));
        assertEquals(Optional.of(longList), OcoOrderIds.listOfLeg(longStop));
    }

    private static BinanceUserDataStreamClient.ExecutionReport report(String clientOrderId, String status,
                                                                      String cumQty, String price) throws Exception {
        String json = """
                {"e":"executionReport","E":1757930000000,"s":"BTCUSDT","c":"%s","S":"SELL","o":"LIMIT_MAKER",
                 "q":"0.00049","p":"0","x":"TRADE","X":"%s","i":10,"l":"%s","z":"%s","L":"%s",
                 "n":"0","N":"USDT","T":1757930000000,"t":1,"C":""}
                """.formatted(clientOrderId, status, cumQty, cumQty, price);
        return BinanceUserDataStreamClient.parseExecutionReport(new ObjectMapper().readTree(json));
    }

    private static PredictionVector prediction(Signal signal) {
        return PredictionVector.builder()
                .instrument("BTCUSDT")
                .timestamp(NOW)
                .signal(signal)
                .probabilityUp(new BigDecimal("0.5"))
                .probabilityDown(new BigDecimal("0.5"))
                .confidence(new BigDecimal("0.5"))
                .price(new BigDecimal("100000"))
                .modelVersion("test")
                .reason("test")
                .build();
    }

    private static CandleEvent candle(String close) {
        BigDecimal price = new BigDecimal(close);
        return new CandleEvent("BTCUSDT", NOW, NOW.plusSeconds(60), price, price, price, price, BigDecimal.ONE, 1);
    }
}
