package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.adapter.BinanceAggTradeArchive;
import dev.romeo.btctradingengine.adapter.BinanceKlineClient;
import dev.romeo.btctradingengine.derivatives.BinanceFuturesClient;
import dev.romeo.btctradingengine.derivatives.BinanceMetricsArchive;
import dev.romeo.btctradingengine.derivatives.DerivativesHistory;
import dev.romeo.btctradingengine.model.CandleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real Binance mainnet history for on-demand backtests, moved out of DashboardController (issue #108).
 * Always mainnet regardless of binance.rest.url, since testnet price/volume does not reflect the real
 * market. The kline client keeps its own short in-memory cache per (symbol, interval, days).
 */
public class BinanceBacktestDataLoader implements BacktestData.Loader {
    private static final Logger logger = LoggerFactory.getLogger(BinanceBacktestDataLoader.class);

    /**
     * What the loader needs from the configuration: market.symbol, market.binance.interval,
     * feature.flow.large.trade.notional, derivatives.enabled and the derivatives staleness/OI windows.
     */
    public record Settings(String symbol, String interval, BigDecimal largeTradeNotional, boolean derivativesEnabled,
                           Duration derivativesStaleAfter, Duration openInterestChangeWindow) {
    }

    private final BinanceKlineClient klineClient;
    private final BinanceKlineClient futuresKlineClient;
    private final BinanceFuturesClient futuresClient;
    private final BinanceMetricsArchive metricsArchive;
    private final BinanceAggTradeArchive aggTradeArchive;
    private final Settings settings;

    public BinanceBacktestDataLoader(BinanceKlineClient klineClient, BinanceKlineClient futuresKlineClient,
                                     BinanceFuturesClient futuresClient, BinanceMetricsArchive metricsArchive,
                                     BinanceAggTradeArchive aggTradeArchive, Settings settings) {
        this.klineClient = klineClient;
        this.futuresKlineClient = futuresKlineClient;
        this.futuresClient = futuresClient;
        this.metricsArchive = metricsArchive;
        this.aggTradeArchive = aggTradeArchive;
        this.settings = settings;
    }

    @Override
    public BacktestData load(int days, boolean sizeSplit) throws Exception {
        String symbol = settings.symbol();
        String interval = settings.interval();
        List<CandleEvent> candles = klineClient.loadClosedCandlesRange(symbol, interval, days);
        if (candles.isEmpty()) {
            return new BacktestData(symbol, interval, days, candles, DerivativesHistory.forBacktest(settings.derivativesStaleAfter(), settings.openInterestChangeWindow()), false, 0);
        }
        if (sizeSplit) {
            candles = withTradeSizeSplit(candles);
        }
        DerivativesHistory derivatives = loadDerivatives(candles, days);
        return new BacktestData(symbol, interval, days, candles, derivatives, !derivatives.isEmpty(),
                BinanceAggTradeArchive.candlesWithSizeSplit(candles));
    }

    /**
     * Replaces each candle's kline flow (taker split only) with flow rebuilt from the aggTrades dumps,
     * so the size split exists in the backtest (issue #51). Minutes without a dump keep their kline
     * flow, and a failure returns the candles unchanged instead of failing the request.
     */
    private List<CandleEvent> withTradeSizeSplit(List<CandleEvent> candles) {
        try {
            LocalDate from = candles.get(0).openTime().atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate to = candles.get(candles.size() - 1).closeTime().atZone(ZoneOffset.UTC).toLocalDate();
            Map<Instant, BinanceAggTradeArchive.SizeBuckets> buckets =
                    aggTradeArchive.load(settings.symbol(), from, to);
            BigDecimal largeTradeNotional = settings.largeTradeNotional();

            List<CandleEvent> merged = new ArrayList<>(candles.size());
            for (CandleEvent candle : candles) {
                BinanceAggTradeArchive.SizeBuckets candleBuckets = buckets.get(candle.openTime());
                merged.add(candleBuckets == null ? candle : new CandleEvent(
                        candle.instrument(), candle.openTime(), candle.closeTime(),
                        candle.open(), candle.high(), candle.low(), candle.close(),
                        candle.volume(), candle.tickCount(), candleBuckets.toTradeFlow(largeTradeNotional)));
            }
            return merged;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted loading aggTrades for the backtest size split");
            return candles;
        } catch (Exception e) {
            logger.warn("Could not load aggTrades for the backtest size split, continuing with kline flow: {}", e.getMessage());
            return candles;
        }
    }

    /**
     * Derivatives history for the backtest range: funding and basis from the futures REST API, open
     * interest and long/short from the data.binance.vision metrics dumps (issue #54). Each source fails
     * on its own - the backtest runs with whatever loaded instead of failing the request.
     */
    private DerivativesHistory loadDerivatives(List<CandleEvent> candles, int days) {
        DerivativesHistory history = DerivativesHistory.forBacktest(settings.derivativesStaleAfter(), settings.openInterestChangeWindow());
        if (!settings.derivativesEnabled()) {
            return history;
        }
        try {
            futuresKlineClient.loadClosedCandlesRange(settings.symbol(), settings.interval(), days)
                    .forEach(kline -> history.addPerpClose(kline.openTime(), kline.close()));
            // One funding interval before the first candle, so it already has a settled rate
            Instant from = candles.get(0).openTime().minus(DerivativesHistory.FUNDING_INTERVAL);
            Instant to = candles.get(candles.size() - 1).closeTime();
            futuresClient.fundingRates(settings.symbol(), from, to)
                    .forEach(rate -> history.addFundingRate(rate.time(), rate.value()));
        } catch (Exception e) {
            logger.warn("Could not load derivatives history for the backtest, continuing without it: {}", e.getMessage());
        }
        try {
            // Start one change window early, so the first candles already have an open interest to compare against
            LocalDate from = candles.get(0).openTime()
                    .minus(settings.openInterestChangeWindow())
                    .atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate to = candles.get(candles.size() - 1).closeTime().atZone(ZoneOffset.UTC).toLocalDate();
            metricsArchive.load(settings.symbol(), from, to).forEach(row -> {
                if (row.openInterest() != null) {
                    history.addOpenInterest(row.publishedAt(), row.openInterest());
                }
                if (row.longShortRatio() != null) {
                    history.addLongShortRatio(row.publishedAt(), row.longShortRatio());
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted loading the open interest / long-short history for the backtest");
        } catch (Exception e) {
            logger.warn("Could not load open interest / long-short history for the backtest, continuing without it: {}",
                    e.getMessage());
        }
        return history;
    }
}
