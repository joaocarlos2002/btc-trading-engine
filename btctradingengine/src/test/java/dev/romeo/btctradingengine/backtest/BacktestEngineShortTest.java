package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance criterion of issue #67: a backtest with allowShort=false never opens a SELL, so its
 * report only contains trades that the spot market could actually have executed.
 */
public class BacktestEngineShortTest {

    private static BacktestEngine engine(boolean allowShort) {
        return new BacktestEngine(new BigDecimal("0.001"), BigDecimal.ZERO, BigDecimal.ZERO, allowShort);
    }

    @Test
    public void sellDoesNotOpenATradeByDefault() {
        BacktestEngine engine = new BacktestEngine(new BigDecimal("0.001"));

        engine.processPrediction(prediction(Signal.SELL), candle("100"));

        assertTrue(engine.getOpenTrade().isEmpty(), "spot cannot short, so a SELL opens nothing");
        assertEquals(0, engine.getTradeCount());
    }

    @Test
    public void sellStillClosesAnOpenBuyWhenShortsAreOff() {
        BacktestEngine engine = engine(false);
        engine.processPrediction(prediction(Signal.BUY), candle("100"));

        engine.processPrediction(prediction(Signal.SELL), candle("110"));

        assertTrue(engine.getOpenTrade().isEmpty(), "the SELL must not reverse into a short");
        assertEquals(1, engine.getClosedTrades().size(), "the SELL must still close the open BUY");
        Trade closed = engine.getClosedTrades().get(0);
        assertEquals(Signal.BUY, closed.getSignal());
        assertEquals(new BigDecimal("110"), closed.getExitPrice());
    }

    @Test
    public void aLaterBuyStillEntersAfterAVetoedShort() {
        BacktestEngine engine = engine(false);

        engine.processPrediction(prediction(Signal.SELL), candle("100"));
        engine.processPrediction(prediction(Signal.BUY), candle("101"));

        assertEquals(Signal.BUY, engine.getOpenTrade().orElseThrow().getSignal());
    }

    @Test
    public void sellOpensAShortWhenExplicitlyAllowed() {
        BacktestEngine engine = engine(true);

        engine.processPrediction(prediction(Signal.SELL), candle("100"));

        assertEquals(Signal.SELL, engine.getOpenTrade().orElseThrow().getSignal());
    }

    @Test
    public void paramsDefaultToNoShorting() {
        assertEquals(false, BacktestParams.fromConfig().allowShort(),
                "trading.allow.short ships off, so /backtest must not count shorts unless asked");
    }

    private PredictionVector prediction(Signal signal) {
        return PredictionVector.builder()
                .instrument("BTC/USD")
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
        return new CandleEvent("BTC/USD", openTime, openTime.plusSeconds(60),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close),
                BigDecimal.ONE, 1);
    }
}
