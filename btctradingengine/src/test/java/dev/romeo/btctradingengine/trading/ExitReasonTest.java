package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Issue #86: every exit keeps its own reason, and failed entries do not count as trades. */
public class ExitReasonTest {

    private static final class FailingBuyExecutor extends BinanceOrderExecutor {
        FailingBuyExecutor() {
            super("test-key", "test-secret");
        }

        @Override
        public OrderResult executeBuyMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            return new OrderResult(false, null, BigDecimal.ZERO, BigDecimal.ZERO, "rejected");
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

    @Test
    public void signalReversalIsRecordedAsSuch() {
        PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
        manager.processPrediction(prediction(Signal.BUY), candle("100"));

        manager.processPrediction(prediction(Signal.SELL), candle("101"));

        Position closed = manager.getClosedPositions().get(0);
        assertEquals(ExitReason.SIGNAL_REVERSAL, closed.getExitReason());
        assertEquals(1, manager.getTotalTrades());
        assertEquals(1, manager.getWinTrades());
    }

    @Test
    public void failedEntryOrderIsRecordedAndExcludedFromStats() {
        PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
        manager.setOrderIoExecutor(Runnable::run);
        manager.setRealTradingMode(new FailingBuyExecutor(),
                new PortfolioManager(new BigDecimal("1000"), new BigDecimal("5")), "BTCUSDT");
        manager.markReconciliationComplete();

        manager.processPrediction(prediction(Signal.BUY), candle("100"));

        assertEquals(1, manager.getClosedPositions().size());
        assertEquals(ExitReason.ORDER_FAILED, manager.getClosedPositions().get(0).getExitReason());
        assertEquals(0, manager.getTotalTrades());
        assertEquals(0, BigDecimal.ZERO.compareTo(manager.getTotalPnL()));
        assertEquals(0, manager.getValidationReport().getTotalTrades());
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
