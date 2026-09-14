package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BacktestEngineEntryGuardTest {

    @Test
    public void blockedPredictionDoesNotOpenATrade() {
        BacktestEngine engine = new BacktestEngine(new BigDecimal("0.001"));

        engine.processPrediction(prediction(Signal.BUY, false), candle("100"));

        assertTrue(engine.getOpenTrade().isEmpty());
        assertEquals(0, engine.getTradeCount());
    }

    @Test
    public void blockedPredictionStillClosesAnOppositeTrade() {
        BacktestEngine engine = new BacktestEngine(new BigDecimal("0.001"));
        engine.processPrediction(prediction(Signal.BUY, true), candle("100"));

        // toxic flow: the SELL cannot open a short, but it must still close the open long
        engine.processPrediction(prediction(Signal.SELL, false), candle("101"));

        assertTrue(engine.getOpenTrade().isEmpty(), "No new SELL trade while entries are blocked");
        assertEquals(1, engine.getClosedTrades().size(), "The reversal exit must still happen");
    }

    private PredictionVector prediction(Signal signal, boolean entryAllowed) {
        return PredictionVector.builder()
                .instrument("BTC/USD")
                .timestamp(Instant.parse("2026-09-08T10:01:00Z"))
                .signal(signal)
                .probabilityUp(new BigDecimal("0.5"))
                .probabilityDown(new BigDecimal("0.5"))
                .confidence(new BigDecimal("0.5"))
                .price(new BigDecimal("100"))
                .modelVersion("test")
                .entryAllowed(entryAllowed)
                .reason("test")
                .build();
    }

    private CandleEvent candle(String close) {
        Instant openTime = Instant.parse("2026-09-08T10:00:00Z");
        return new CandleEvent("BTC/USD", openTime, openTime.plusSeconds(60),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close),
                BigDecimal.ONE, 1);
    }
}
