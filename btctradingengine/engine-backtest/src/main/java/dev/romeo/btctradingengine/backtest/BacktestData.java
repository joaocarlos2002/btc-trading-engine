package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.feature.DerivativesLookup;
import dev.romeo.btctradingengine.model.CandleEvent;

import java.util.List;

/**
 * Everything one backtest job downloads, once, before running (issue #108). The sweep and walk-forward
 * runs share it read-only.
 */
public record BacktestData(
        String symbol,
        String interval,
        int days,
        List<CandleEvent> candles,
        DerivativesLookup derivatives,
        boolean derivativesLoaded,
        long sizeSplitCandles
) {
    public BacktestData {
        candles = List.copyOf(candles);
    }

    /** Where the data comes from: Binance in production, synthetic candles in tests. */
    @FunctionalInterface
    public interface Loader {
        BacktestData load(int days, boolean sizeSplit) throws Exception;
    }
}
