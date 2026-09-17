package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance criterion of issue #67: in real mode a SELL with no open position sends no order, since
 * the spot market has no short selling.
 */
public class PositionManagerShortTest {

    /** Records the orders that would have gone to Binance instead of sending them. */
    private static final class RecordingExecutor extends BinanceOrderExecutor {
        private final List<String> orders = new ArrayList<>();

        RecordingExecutor() {
            super("test-key", "test-secret", "https://testnet.binance.vision", 10, 1000, 60000);
        }

        @Override
        public OrderResult executeBuyMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            orders.add("BUY " + quantity);
            return new OrderResult(true, "1", quantity, new BigDecimal("100"), null);
        }

        @Override
        public OrderResult executeSellMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            orders.add("SELL " + quantity);
            return new OrderResult(true, "2", quantity, new BigDecimal("100"), null);
        }

        // Fixed answers so the test never talks to Binance.
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

    private static PositionManager manager() {
        return new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
    }

    @Test
    public void simulationDoesNotOpenAShortByDefault() {
        PositionManager manager = manager();

        manager.processPrediction(prediction(Signal.SELL), candle("100"));

        assertTrue(manager.getOpenPosition().isEmpty(), "shorts are off by default");
    }

    @Test
    public void simulationOpensAShortWhenExplicitlyAllowed() {
        PositionManager manager = manager();
        manager.setAllowShort(true);

        manager.processPrediction(prediction(Signal.SELL), candle("100"));

        assertEquals(Signal.SELL, manager.getOpenPosition().orElseThrow().getSignal());
    }

    @Test
    public void sellStillClosesAnOpenBuyWhenShortsAreOff() {
        PositionManager manager = manager();
        manager.processPrediction(prediction(Signal.BUY), candle("100"));
        assertEquals(Signal.BUY, manager.getOpenPosition().orElseThrow().getSignal());

        manager.processPrediction(prediction(Signal.SELL), candle("101"));

        assertTrue(manager.getOpenPosition().isEmpty(), "the SELL must not reverse into a short");
        assertEquals(1, manager.getClosedPositions().size(), "the SELL must still close the open BUY");
    }

    @Test
    public void realModeSendsNoOrderForASellWithoutPosition() {
        RecordingExecutor executor = new RecordingExecutor();
        PositionManager manager = manager();
        // Even explicitly allowed, real spot must refuse: there is nothing to sell.
        manager.setAllowShort(true);
        manager.setOrderIoExecutor(Runnable::run);
        manager.setRealTradingMode(executor, new PortfolioManager(new BigDecimal("1000"), new BigDecimal("5")), "BTCUSDT");
        manager.markReconciliationComplete();

        manager.processPrediction(prediction(Signal.SELL), candle("100"));

        assertTrue(manager.getOpenPosition().isEmpty(), "no short position may be opened on spot");
        assertEquals(List.of(), executor.orders, "no order may reach Binance");
    }

    @Test
    public void realModeStillOpensABuy() {
        RecordingExecutor executor = new RecordingExecutor();
        PositionManager manager = manager();
        manager.setOrderIoExecutor(Runnable::run);
        manager.setRealTradingMode(executor, new PortfolioManager(new BigDecimal("1000"), new BigDecimal("5")), "BTCUSDT");
        manager.markReconciliationComplete();

        manager.processPrediction(prediction(Signal.BUY), candle("100"));

        assertEquals(Signal.BUY, manager.getOpenPosition().orElseThrow().getSignal());
        assertEquals(1, executor.orders.size(), "the BUY entry must still be sent");
        assertTrue(executor.orders.get(0).startsWith("BUY"), executor.orders.toString());
    }

    private PredictionVector prediction(Signal signal) {
        return PredictionVector.builder()
                .instrument("BTCUSDT")
                .timestamp(Instant.parse("2026-09-08T10:01:00Z"))
                .signal(signal)
                .probabilityUp(new BigDecimal("0.5"))
                .probabilityDown(new BigDecimal("0.5"))
                .confidence(new BigDecimal("0.5"))
                .price(new BigDecimal("100"))
                .modelVersion("test")
                .reason("test")
                .build();
    }

    private CandleEvent candle(String close) {
        Instant openTime = Instant.parse("2026-09-08T10:00:00Z");
        return new CandleEvent("BTCUSDT", openTime, openTime.plusSeconds(60),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close),
                BigDecimal.ONE, 1);
    }
}
