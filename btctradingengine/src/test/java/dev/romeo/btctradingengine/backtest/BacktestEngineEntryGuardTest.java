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

    @Test
    public void filterVetoedReversalClosesTheTradeThroughThePredictor() {
        // End to end with the real predictor: a SELL vetoed by the filters must still close an open BUY
        BacktestEngine engine = new BacktestEngine(new BigDecimal("0.001"));
        CandleEvent[] current = new CandleEvent[1];
        dev.romeo.btctradingengine.prediction.RuleBasedPredictor predictor =
                new dev.romeo.btctradingengine.prediction.RuleBasedPredictor(
                        p -> engine.processPrediction(p, current[0]), 0.28, -0.28, 1);
        predictor.addRule(new dev.romeo.btctradingengine.prediction.rules.RsiRule());
        double[] filterScore = {0.1};
        predictor.addFilterRule(new dev.romeo.btctradingengine.prediction.SignalRule() {
            @Override public double evaluate(dev.romeo.btctradingengine.feature.FeatureVector features) { return filterScore[0]; }
            @Override public String getName() { return "TestFilter"; }
        });

        // Each new signal is sent twice: the predictor always answers HOLD to the first snapshot of a change
        current[0] = candle("100");
        predictor.onEvent(features("25"));                  // oversold -> BUY candidate
        predictor.onEvent(features("25"));                  // confirmed BUY, filters allow it
        assertEquals(Signal.BUY, engine.getOpenTrade().orElseThrow().getSignal());

        filterScore[0] = -0.5;                              // conditions turn bad
        current[0] = candle("105");
        predictor.onEvent(features("75"));                  // overbought -> SELL candidate
        predictor.onEvent(features("75"));                  // confirmed SELL, vetoed by the filters

        assertEquals(1, engine.getClosedTrades().size(), "The vetoed SELL must still close the BUY");
        assertTrue(engine.getOpenTrade().isEmpty(), "The vetoed SELL must not open a short");
    }

    private dev.romeo.btctradingengine.feature.FeatureVector features(String rsi) {
        return dev.romeo.btctradingengine.feature.FeatureVector.builder()
                .instrument("BTC/USD").timestamp(Instant.parse("2026-09-08T10:01:00Z"))
                .rsiValue(new BigDecimal(rsi))
                .price(new BigDecimal("100")).tickCount(10)
                .build();
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
