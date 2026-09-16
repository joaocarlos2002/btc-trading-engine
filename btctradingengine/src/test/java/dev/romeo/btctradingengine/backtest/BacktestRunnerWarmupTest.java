package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.feature.DerivativesLookup;
import dev.romeo.btctradingengine.model.CandleEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BacktestRunnerWarmupTest {

    private static final BigDecimal CAPITAL = BigDecimal.valueOf(100);

    @Test
    void warmupCandlesNeverOpenATrade() {
        List<CandleEvent> candles = SyntheticCandles.hourly(10);
        List<CandleEvent> warmup = candles.subList(0, 72);
        List<CandleEvent> trading = candles.subList(72, candles.size());
        BacktestParams params = SyntheticCandles.fastParams();

        // Run without a warmup, the same first 72 candles do open trades
        BacktestReport full = new BacktestRunner().run(candles, CAPITAL, params, DerivativesLookup.NONE);
        Instant tradingStart = trading.get(0).openTime();
        assertTrue(full.trades().stream().anyMatch(t -> t.getEntryTime().isBefore(tradingStart)),
                "the fixture must trade inside the warmup range, otherwise this test proves nothing");

        BacktestReport warmed = new BacktestRunner().run(warmup, trading, CAPITAL, params, DerivativesLookup.NONE);
        assertTrue(warmed.getTotalTrades() > 0);
        assertTrue(warmed.trades().stream().noneMatch(t -> t.getEntryTime().isBefore(tradingStart)));
    }

    @Test
    void onlyWarmupAndNoTradingCandlesGivesAnEmptyReport() {
        List<CandleEvent> candles = SyntheticCandles.hourly(5);
        BacktestReport report = new BacktestRunner().run(candles, List.of(), CAPITAL,
                SyntheticCandles.fastParams(), DerivativesLookup.NONE);
        assertEquals(0, report.getTotalTrades());
    }

    @Test
    void warmupChangesTheIndicatorsTheWindowStartsWith() {
        List<CandleEvent> candles = SyntheticCandles.hourly(6);
        List<CandleEvent> trading = candles.subList(72, candles.size());
        BacktestParams params = SyntheticCandles.fastParams();

        BacktestReport cold = new BacktestRunner().run(List.of(), trading, CAPITAL, params, DerivativesLookup.NONE);
        BacktestReport warmed = new BacktestRunner().run(candles.subList(0, 72), trading, CAPITAL, params,
                DerivativesLookup.NONE);
        List<Instant> coldEntries = cold.trades().stream().map(Trade::getEntryTime).toList();
        List<Instant> warmedEntries = warmed.trades().stream().map(Trade::getEntryTime).toList();
        assertNotEquals(coldEntries, warmedEntries);
    }
}
