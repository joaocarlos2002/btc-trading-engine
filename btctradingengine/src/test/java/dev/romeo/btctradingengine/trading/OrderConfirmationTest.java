package dev.romeo.btctradingengine.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance criteria of issue #63: an executionReport with a textual "c" keeps the position quantity,
 * and a timeout without a report asks Binance instead of assuming a fill of 0.
 */
public class OrderConfirmationTest {

    private static final String ENTRY_ID = "btce-BTCUSDT-POS_1-entry";

    /** Fakes Binance: the BUY fills fully, and queryOrder answers what the test sets. */
    private static final class FakeExecutor extends BinanceOrderExecutor {
        BigDecimal executedQty;
        Consumer<BigDecimal> onBuy = qty -> {};
        Optional<QueriedOrder> queryAnswer = Optional.empty();
        int queries;

        FakeExecutor() {
            super("test-key", "test-secret");
        }

        @Override
        public OrderResult executeBuyMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            executedQty = quantity;
            onBuy.accept(quantity);
            return new OrderResult(true, "12345", quantity, new BigDecimal("100"), null);
        }

        @Override
        public OrderResult executeSellMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            return new OrderResult(true, "12346", quantity, new BigDecimal("100"), null);
        }

        @Override
        public Optional<QueriedOrder> queryOrder(String symbol, String clientOrderId) {
            queries++;
            return queryAnswer;
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

    private final FakeExecutor executor = new FakeExecutor();
    private final OrderConfirmationManager confirmations = new OrderConfirmationManager(executor, false);
    private final PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));

    OrderConfirmationTest() {
        manager.setOrderIoExecutor(Runnable::run);
        manager.setRealTradingMode(executor, new PortfolioManager(new BigDecimal("1000"), new BigDecimal("5")), "BTCUSDT");
        manager.markReconciliationComplete();
        manager.setOrderConfirmationManager(confirmations);
    }

    @AfterEach
    void shutdown() {
        confirmations.shutdown();
    }

    @Test
    public void executionReportWithTextualClientOrderIdKeepsTheExecutedQuantity() throws Exception {
        // The report arrives before the REST response returns, as a MARKET fill can
        executor.onBuy = qty -> confirmations.processExecutionReport(report(ENTRY_ID, "FILLED", qty));

        manager.processPrediction(prediction(), candle());

        Position pos = manager.getOpenPosition().orElseThrow();
        assertTrue(executor.executedQty.signum() > 0);
        assertEquals(0, pos.getQuantity().compareTo(executor.executedQty), "quantity=" + pos.getQuantity());
        assertFalse(confirmations.isPending(ENTRY_ID), "the report must have confirmed the order");

        confirmations.checkTimeouts(Instant.now().plus(OrderConfirmationManager.QUERY_GIVE_UP));
        assertEquals(0, executor.queries, "a confirmed order must not be looked up again");
        assertEquals(0, pos.getQuantity().compareTo(executor.executedQty));
    }

    @Test
    public void timeoutWithoutReportQueriesBinanceAndKeepsTheQuantity() {
        manager.processPrediction(prediction(), candle());
        Position pos = manager.getOpenPosition().orElseThrow();
        BigDecimal bought = pos.getQuantity();
        assertTrue(bought.signum() > 0);
        executor.queryAnswer = Optional.of(new BinanceOrderExecutor.QueriedOrder(
                12345, ENTRY_ID, "FILLED", bought, new BigDecimal("100")));

        confirmations.checkTimeouts(Instant.now().plus(OrderConfirmationManager.REPORT_TIMEOUT).plusSeconds(1));

        assertEquals(1, executor.queries, "the timeout must ask Binance");
        assertEquals(0, pos.getQuantity().compareTo(bought), "quantity=" + pos.getQuantity());
        assertFalse(confirmations.isPending(ENTRY_ID));
    }

    @Test
    public void timeoutWithBinanceUnreachableNeverZeroesTheQuantity() {
        manager.processPrediction(prediction(), candle());
        Position pos = manager.getOpenPosition().orElseThrow();
        BigDecimal bought = pos.getQuantity();

        confirmations.checkTimeouts(Instant.now().plus(OrderConfirmationManager.REPORT_TIMEOUT).plusSeconds(1));
        assertTrue(confirmations.isPending(ENTRY_ID), "an unanswered query keeps the order pending");

        confirmations.checkTimeouts(Instant.now().plus(OrderConfirmationManager.QUERY_GIVE_UP).plusSeconds(1));
        assertEquals(2, executor.queries);
        assertFalse(confirmations.isPending(ENTRY_ID), "gives up after QUERY_GIVE_UP");
        assertEquals(0, pos.getQuantity().compareTo(bought), "quantity=" + pos.getQuantity());
        assertTrue(manager.getOpenPosition().isPresent(), "the position stays open for a manual check");
    }

    @Test
    public void parsesOrderIdAndClientOrderIdFromTheirOwnFields() throws Exception {
        BinanceUserDataStreamClient.ExecutionReport filled = report(ENTRY_ID, "FILLED", new BigDecimal("0.5"));
        assertEquals(12345, filled.orderId());
        assertEquals(ENTRY_ID, filled.clientOrderId());

        // On a cancel, "c" is the cancel request's id and the original id comes in "C"
        BinanceUserDataStreamClient.ExecutionReport canceled = BinanceUserDataStreamClient.parseExecutionReport(
                new ObjectMapper().readTree(json("cancel-request-1", ENTRY_ID, "CANCELED", BigDecimal.ZERO)));
        assertEquals(ENTRY_ID, canceled.clientOrderId());
    }

    private static BinanceUserDataStreamClient.ExecutionReport report(String clientOrderId, String status, BigDecimal cumQty) {
        try {
            return BinanceUserDataStreamClient.parseExecutionReport(
                    new ObjectMapper().readTree(json(clientOrderId, "", status, cumQty)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String json(String c, String originalC, String status, BigDecimal cumQty) {
        return """
                {"e":"executionReport","E":1757930000000,"s":"BTCUSDT","c":"%s","S":"BUY","o":"MARKET",
                 "q":"%s","p":"0.00000000","x":"TRADE","X":"%s","i":12345,"l":"%s","z":"%s","L":"100.00",
                 "n":"0","N":"BTC","T":1757930000000,"t":1,"C":"%s"}
                """.formatted(c, cumQty.toPlainString(), status, cumQty.toPlainString(), cumQty.toPlainString(), originalC);
    }

    private PredictionVector prediction() {
        return PredictionVector.builder()
                .instrument("BTCUSDT")
                .timestamp(Instant.parse("2026-09-08T10:01:00Z"))
                .signal(Signal.BUY)
                .probabilityUp(new BigDecimal("0.5"))
                .probabilityDown(new BigDecimal("0.5"))
                .confidence(new BigDecimal("0.5"))
                .price(new BigDecimal("100"))
                .modelVersion("test")
                .reason("test")
                .build();
    }

    private CandleEvent candle() {
        Instant openTime = Instant.parse("2026-09-08T10:00:00Z");
        BigDecimal close = new BigDecimal("100");
        return new CandleEvent("BTCUSDT", openTime, openTime.plusSeconds(60), close, close, close, close,
                BigDecimal.ONE, 1);
    }
}
