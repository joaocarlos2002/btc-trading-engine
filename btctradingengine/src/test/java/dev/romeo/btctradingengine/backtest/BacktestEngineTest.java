package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class BacktestEngineTest {

    @Test
    public void openAndCloseBuyTrade() {
        BacktestEngine engine = new BacktestEngine(new BigDecimal("0.001"));

        // Candle 1: BUY signal at 100
        CandleEvent candle1 = createCandle("100", 0);
        PredictionVector pred1 = createPrediction(Signal.BUY, "100");
        engine.processPrediction(pred1, candle1);

        assertTrue(engine.getOpenTrade().isPresent(), "Trade should be open after BUY");

        // Candle 2: HOLD (maintain position)
        CandleEvent candle2 = createCandle("105", 1);
        PredictionVector pred2 = createPrediction(Signal.HOLD, "105");
        engine.processPrediction(pred2, candle2);

        assertTrue(engine.getOpenTrade().isPresent(), "Trade should stay open on HOLD");

        // Candle 3: SELL signal at 110 (close at profit)
        CandleEvent candle3 = createCandle("110", 2);
        PredictionVector pred3 = createPrediction(Signal.SELL, "110");
        engine.processPrediction(pred3, candle3);

        assertFalse(engine.getOpenTrade().isPresent(), "Trade should close on SELL signal");
        assertEquals(1, engine.getClosedTrades().size());

        Trade closedTrade = engine.getClosedTrades().get(0);
        assertEquals(Signal.BUY, closedTrade.getSignal());
        assertEquals(new BigDecimal("100"), closedTrade.getEntryPrice());
        assertEquals(new BigDecimal("110"), closedTrade.getExitPrice());
        assertTrue(closedTrade.isWinner(), "Trade should be profitable (110 > 100)");
    }

    @Test
    public void reversePositionFromBuyToSell() {
        BacktestEngine engine = new BacktestEngine(new BigDecimal("0.001"));

        // Open BUY
        CandleEvent candle1 = createCandle("100", 0);
        PredictionVector pred1 = createPrediction(Signal.BUY, "100");
        engine.processPrediction(pred1, candle1);

        assertTrue(engine.getOpenTrade().isPresent());
        assertEquals(Signal.BUY, engine.getOpenTrade().get().getSignal());

        // Reverse to SELL
        CandleEvent candle2 = createCandle("105", 1);
        PredictionVector pred2 = createPrediction(Signal.SELL, "105");
        engine.processPrediction(pred2, candle2);

        // Old BUY should be closed, new SELL should be open
        assertEquals(1, engine.getClosedTrades().size());
        assertTrue(engine.getOpenTrade().isPresent());
        assertEquals(Signal.SELL, engine.getOpenTrade().get().getSignal());
    }

    @Test
    public void calculateWinRate() {
        BacktestEngine engine = new BacktestEngine(new BigDecimal("0.0"));

        // Winning trade: BUY at 100, SELL at 110
        createAndCloseTrade(engine, Signal.BUY, "100", "110", 0, 1);

        // Losing trade: BUY at 120, SELL at 115
        createAndCloseTrade(engine, Signal.BUY, "120", "115", 2, 3);

        // Winning trade: SELL at 100, BUY at 95
        createAndCloseTrade(engine, Signal.SELL, "100", "95", 4, 5);

        BacktestReport report = engine.generateReport(new BigDecimal("1000"));

        assertEquals(3, report.getTotalTrades());
        assertEquals(2, report.getWinTrades());
        assertEquals(1, report.getLoseTrades());
        assertEquals(new BigDecimal("66.67"), report.getWinRate().setScale(2, java.math.RoundingMode.HALF_UP));
    }

    @Test
    public void appliesCommissionToEntryAndExit() {
        BacktestEngine engine = new BacktestEngine(new BigDecimal("0.001"));

        createAndCloseTrade(engine, Signal.BUY, "100", "110", 0, 1);

        BacktestReport report = engine.generateReport(new BigDecimal("1000"));

        // Gross P&L 10 - (100 + 110) * 0.001 = 9.79
        assertEquals(new BigDecimal("9.79"), report.getTotalPnL().setScale(2, java.math.RoundingMode.HALF_UP));
    }

    @Test
    public void calculateProfitFactor() {
        BacktestEngine engine = new BacktestEngine(new BigDecimal("0.0"));

        // 2 winning trades: +10, +5
        createAndCloseTrade(engine, Signal.BUY, "100", "110", 0, 1);
        createAndCloseTrade(engine, Signal.BUY, "200", "205", 2, 3);

        // 1 losing trade: -20
        createAndCloseTrade(engine, Signal.BUY, "300", "280", 4, 5);

        BacktestReport report = engine.generateReport(new BigDecimal("1000"));

        // Profit factor = 15 / 20 = 0.75
        assertEquals(new BigDecimal("0.75"), report.getProfitFactor().setScale(2, java.math.RoundingMode.HALF_UP));
    }

    @Test
    public void trackBarCount() {
        BacktestEngine engine = new BacktestEngine(new BigDecimal("0.0"));

        CandleEvent candle1 = createCandle("100", 0);
        PredictionVector pred1 = createPrediction(Signal.BUY, "100");
        engine.processPrediction(pred1, candle1);

        CandleEvent candle2 = createCandle("105", 1);
        PredictionVector pred2 = createPrediction(Signal.HOLD, "105");
        engine.processPrediction(pred2, candle2);

        CandleEvent candle3 = createCandle("110", 2);
        PredictionVector pred3 = createPrediction(Signal.SELL, "110");
        engine.processPrediction(pred3, candle3);

        Trade trade = engine.getClosedTrades().get(0);
        assertEquals(3, trade.getBarCount(), "Trade should span 3 bars");
    }

    private void createAndCloseTrade(BacktestEngine engine, Signal signal, String entry, String exit, int entryBar, int exitBar) {
        CandleEvent candleEntry = createCandle(entry, entryBar);
        PredictionVector predEntry = createPrediction(signal, entry);
        engine.processPrediction(predEntry, candleEntry);

        CandleEvent candleExit = createCandle(exit, exitBar);
        Signal exitSignal = signal == Signal.BUY ? Signal.SELL : Signal.BUY;
        PredictionVector predExit = createPrediction(exitSignal, exit);
        engine.processPrediction(predExit, candleExit);
    }

    private CandleEvent createCandle(String price, int barIndex) {
        Instant baseTime = Instant.parse("2026-09-08T10:00:00Z");
        Instant candleTime = baseTime.plusSeconds(barIndex * 900); // 15 min bars

        return new CandleEvent(
                "BTC/USD",
                candleTime,
                candleTime.plusSeconds(900),
                new BigDecimal(price),
                new BigDecimal(price).multiply(new BigDecimal("1.01")),
                new BigDecimal(price).multiply(new BigDecimal("0.99")),
                new BigDecimal(price),
                new BigDecimal("1000"),
                40
        );
    }

    private PredictionVector createPrediction(Signal signal, String price) {
        return PredictionVector.builder()
                .instrument("BTC/USD")
                .timestamp(Instant.now())
                .signal(signal)
                .probabilityUp(new BigDecimal("0.6"))
                .probabilityDown(new BigDecimal("0.4"))
                .confidence(new BigDecimal("0.7"))
                .price(new BigDecimal(price))
                .modelVersion("rules-v1")
                .reason("Test")
                .build();
    }
}


