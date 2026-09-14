package dev.romeo.btctradingengine.dashboard;

import dev.romeo.btctradingengine.adapter.BinanceAggTradeArchive;
import dev.romeo.btctradingengine.adapter.BinanceKlineClient;
import dev.romeo.btctradingengine.backtest.BacktestParams;
import dev.romeo.btctradingengine.backtest.BacktestReport;
import dev.romeo.btctradingengine.backtest.BacktestRunner;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.derivatives.BinanceFuturesClient;
import dev.romeo.btctradingengine.derivatives.BinanceMetricsArchive;
import dev.romeo.btctradingengine.derivatives.DerivativesHistory;
import dev.romeo.btctradingengine.indicator.VwapAnchor;
import dev.romeo.btctradingengine.model.CandleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final DashboardState state;
    private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);

    private final BinanceKlineClient klineClient = new BinanceKlineClient();
    private final BinanceKlineClient futuresKlineClient = BinanceKlineClient.usdmFutures();
    private final BinanceFuturesClient futuresClient = new BinanceFuturesClient();
    private final BinanceMetricsArchive metricsArchive = new BinanceMetricsArchive();
    private final BinanceAggTradeArchive aggTradeArchive = new BinanceAggTradeArchive();

    public DashboardController(DashboardState state) {
        this.state = state;
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
            Map<java.time.Instant, BinanceAggTradeArchive.SizeBuckets> buckets =
                    aggTradeArchive.load(Config.getMarketSymbol(), from, to);
            BigDecimal largeTradeNotional = Config.getLargeTradeNotional();

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
    private DerivativesHistory loadBacktestDerivatives(List<CandleEvent> candles, int days) {
        DerivativesHistory history = DerivativesHistory.forBacktest();
        if (!Config.isDerivativesEnabled()) {
            return history;
        }
        try {
            futuresKlineClient.loadClosedCandlesRange(Config.getMarketSymbol(), Config.getBinanceKlineInterval(), days)
                    .forEach(kline -> history.addPerpClose(kline.openTime(), kline.close()));
            // One funding interval before the first candle, so it already has a settled rate
            Instant from = candles.get(0).openTime().minus(DerivativesHistory.FUNDING_INTERVAL);
            Instant to = candles.get(candles.size() - 1).closeTime();
            futuresClient.fundingRates(Config.getMarketSymbol(), from, to)
                    .forEach(rate -> history.addFundingRate(rate.time(), rate.value()));
        } catch (Exception e) {
            logger.warn("Could not load derivatives history for the backtest, continuing without it: {}", e.getMessage());
        }
        try {
            // Start one change window early, so the first candles already have an open interest to compare against
            LocalDate from = candles.get(0).openTime()
                    .minus(Duration.ofMinutes(Config.getOpenInterestChangeMinutes()))
                    .atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate to = candles.get(candles.size() - 1).closeTime().atZone(ZoneOffset.UTC).toLocalDate();
            metricsArchive.load(Config.getMarketSymbol(), from, to).forEach(row -> {
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

    @GetMapping("/candles/latest")
    public ResponseEntity<?> latestCandle() { return state.latestCandle().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build()); }

    @GetMapping("/candles/history")
    public Object candleHistory(@RequestParam(defaultValue = "100") int limit) { return state.candles(limit); }

    @GetMapping("/metrics/current")
    public ResponseEntity<?> metrics() { return state.latestFeatures().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build()); }

    @GetMapping("/prediction/current")
    public ResponseEntity<?> prediction() { return state.latestPrediction().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build()); }

    @GetMapping("/trades/open")
    public ResponseEntity<?> openTrade() { return state.openPosition().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build()); }

    @PostMapping("/trades/manual/buy")
    public ResponseEntity<DashboardState.ManualBuyResult> manualBuy() {
        DashboardState.ManualBuyResult result = state.manualBuy();
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/trades/manual/close")
    public ResponseEntity<DashboardState.ManualBuyResult> manualClose() {
        DashboardState.ManualBuyResult result = state.manualClose();
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }

    @GetMapping("/trades/closed")
    public Object closedTrades(@RequestParam(defaultValue = "50") int limit) { return state.closedPositions(limit); }

    @GetMapping("/stats")
    public DashboardState.Stats stats() { return state.stats(); }

    /**
     * Runs the current strategy config (indicator periods, thresholds, target/stop) against
     * real Binance mainnet history and returns the resulting report - lets you re-validate
     * the strategy's edge on demand, e.g. after tuning thresholds or periodically over time.
     * Always uses mainnet data regardless of binance.rest.url, since testnet price/volume
     * does not reflect the real market.
     */
    @GetMapping("/backtest")
    public ResponseEntity<?> backtest(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "false") boolean sizeSplit,
            @RequestParam(required = false) Integer smaPeriod,
            @RequestParam(required = false) Integer emaPeriod,
            @RequestParam(required = false) Integer rsiPeriod,
            @RequestParam(required = false) Integer atrPeriod,
            @RequestParam(required = false) Integer macdFastPeriod,
            @RequestParam(required = false) Integer macdSlowPeriod,
            @RequestParam(required = false) Integer macdSignalPeriod,
            @RequestParam(required = false) Integer volatilityShortPeriods,
            @RequestParam(required = false) Integer volatilityLongPeriods,
            @RequestParam(required = false) Integer volumeAveragePeriods,
            @RequestParam(required = false) Integer adxPeriod,
            @RequestParam(required = false) Integer bollingerPeriod,
            @RequestParam(required = false) BigDecimal bollingerStdDev,
            @RequestParam(required = false) Integer mfiPeriod,
            @RequestParam(required = false) Integer donchianPeriod,
            @RequestParam(required = false) VwapAnchor vwapAnchor,
            @RequestParam(required = false) Integer vwapRollingPeriods,
            @RequestParam(required = false) Integer priceActionLookback,
            @RequestParam(required = false) Integer priceActionSwingStrength,
            @RequestParam(required = false) Integer cvdPeriod,
            @RequestParam(required = false) Integer emaSlopePeriods,
            @RequestParam(required = false) Integer vpinBuckets,
            @RequestParam(required = false) Integer vpinBucketCandles,
            @RequestParam(required = false) BigDecimal absorptionDeltaMin,
            @RequestParam(required = false) BigDecimal absorptionVolumeRatioMin,
            @RequestParam(required = false) BigDecimal absorptionMaxMoveAtr,
            @RequestParam(required = false) Integer absorptionWindow,
            @RequestParam(required = false) BigDecimal rsiOversold,
            @RequestParam(required = false) BigDecimal rsiNeutralLow,
            @RequestParam(required = false) BigDecimal rsiNeutralHigh,
            @RequestParam(required = false) BigDecimal rsiOverbought,
            @RequestParam(required = false) BigDecimal smaDistanceExtreme,
            @RequestParam(required = false) BigDecimal smaDistanceModerate,
            @RequestParam(required = false) BigDecimal macdStrongHistogramAtrRatio,
            @RequestParam(required = false) BigDecimal atrVolatilityLow,
            @RequestParam(required = false) BigDecimal atrVolatilityNormal,
            @RequestParam(required = false) BigDecimal atrVolatilityHigh,
            @RequestParam(required = false) BigDecimal volatilityRatioHigh,
            @RequestParam(required = false) BigDecimal mfiOversold,
            @RequestParam(required = false) BigDecimal mfiNeutralLow,
            @RequestParam(required = false) BigDecimal mfiNeutralHigh,
            @RequestParam(required = false) BigDecimal mfiOverbought,
            @RequestParam(required = false) BigDecimal adxTrendMin,
            @RequestParam(required = false) BigDecimal bollingerSqueezeThreshold,
            @RequestParam(required = false) BigDecimal adxTrendStrong,
            @RequestParam(required = false) Boolean regimeGatingEnabled,
            @RequestParam(required = false) Boolean vpinFilterEnabled,
            @RequestParam(required = false) BigDecimal vpinHighThreshold,
            @RequestParam(required = false) Double buyThreshold,
            @RequestParam(required = false) Double sellThreshold,
            @RequestParam(required = false) Integer confirmationSnapshots,
            @RequestParam(required = false) BigDecimal targetPercent,
            @RequestParam(required = false) BigDecimal stopLossPercent,
            @RequestParam(required = false) BigDecimal commissionRate) {
        int clampedDays = Math.max(1, Math.min(days, 180));
        try {
            List<CandleEvent> candles = klineClient.loadClosedCandlesRange(
                    Config.getMarketSymbol(), Config.getBinanceKlineInterval(), clampedDays);
            if (candles.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "No candles returned for " + Config.getMarketSymbol() + " over " + clampedDays + " days"));
            }

            BacktestParams defaults = BacktestParams.fromConfig();
            BacktestParams params = new BacktestParams(
                    smaPeriod != null ? smaPeriod : defaults.smaPeriod(),
                    emaPeriod != null ? emaPeriod : defaults.emaPeriod(),
                    rsiPeriod != null ? rsiPeriod : defaults.rsiPeriod(),
                    atrPeriod != null ? atrPeriod : defaults.atrPeriod(),
                    macdFastPeriod != null ? macdFastPeriod : defaults.macdFastPeriod(),
                    macdSlowPeriod != null ? macdSlowPeriod : defaults.macdSlowPeriod(),
                    macdSignalPeriod != null ? macdSignalPeriod : defaults.macdSignalPeriod(),
                    volatilityShortPeriods != null ? volatilityShortPeriods : defaults.volatilityShortPeriods(),
                    volatilityLongPeriods != null ? volatilityLongPeriods : defaults.volatilityLongPeriods(),
                    volumeAveragePeriods != null ? volumeAveragePeriods : defaults.volumeAveragePeriods(),
                    adxPeriod != null ? adxPeriod : defaults.adxPeriod(),
                    bollingerPeriod != null ? bollingerPeriod : defaults.bollingerPeriod(),
                    bollingerStdDev != null ? bollingerStdDev : defaults.bollingerStdDev(),
                    mfiPeriod != null ? mfiPeriod : defaults.mfiPeriod(),
                    donchianPeriod != null ? donchianPeriod : defaults.donchianPeriod(),
                    vwapAnchor != null ? vwapAnchor : defaults.vwapAnchor(),
                    vwapRollingPeriods != null ? vwapRollingPeriods : defaults.vwapRollingPeriods(),
                    priceActionLookback != null ? priceActionLookback : defaults.priceActionLookback(),
                    priceActionSwingStrength != null ? priceActionSwingStrength : defaults.priceActionSwingStrength(),
                    cvdPeriod != null ? cvdPeriod : defaults.cvdPeriod(),
                    emaSlopePeriods != null ? emaSlopePeriods : defaults.emaSlopePeriods(),
                    vpinBuckets != null ? vpinBuckets : defaults.vpinBuckets(),
                    vpinBucketCandles != null ? vpinBucketCandles : defaults.vpinBucketCandles(),
                    absorptionDeltaMin != null ? absorptionDeltaMin : defaults.absorptionDeltaMin(),
                    absorptionVolumeRatioMin != null ? absorptionVolumeRatioMin : defaults.absorptionVolumeRatioMin(),
                    absorptionMaxMoveAtr != null ? absorptionMaxMoveAtr : defaults.absorptionMaxMoveAtr(),
                    absorptionWindow != null ? absorptionWindow : defaults.absorptionWindow(),
                    rsiOversold != null ? rsiOversold : defaults.rsiOversold(),
                    rsiNeutralLow != null ? rsiNeutralLow : defaults.rsiNeutralLow(),
                    rsiNeutralHigh != null ? rsiNeutralHigh : defaults.rsiNeutralHigh(),
                    rsiOverbought != null ? rsiOverbought : defaults.rsiOverbought(),
                    smaDistanceExtreme != null ? smaDistanceExtreme : defaults.smaDistanceExtreme(),
                    smaDistanceModerate != null ? smaDistanceModerate : defaults.smaDistanceModerate(),
                    macdStrongHistogramAtrRatio != null ? macdStrongHistogramAtrRatio : defaults.macdStrongHistogramAtrRatio(),
                    atrVolatilityLow != null ? atrVolatilityLow : defaults.atrVolatilityLow(),
                    atrVolatilityNormal != null ? atrVolatilityNormal : defaults.atrVolatilityNormal(),
                    atrVolatilityHigh != null ? atrVolatilityHigh : defaults.atrVolatilityHigh(),
                    volatilityRatioHigh != null ? volatilityRatioHigh : defaults.volatilityRatioHigh(),
                    mfiOversold != null ? mfiOversold : defaults.mfiOversold(),
                    mfiNeutralLow != null ? mfiNeutralLow : defaults.mfiNeutralLow(),
                    mfiNeutralHigh != null ? mfiNeutralHigh : defaults.mfiNeutralHigh(),
                    mfiOverbought != null ? mfiOverbought : defaults.mfiOverbought(),
                    adxTrendMin != null ? adxTrendMin : defaults.adxTrendMin(),
                    bollingerSqueezeThreshold != null ? bollingerSqueezeThreshold : defaults.bollingerSqueezeThreshold(),
                    adxTrendStrong != null ? adxTrendStrong : defaults.adxTrendStrong(),
                    regimeGatingEnabled != null ? regimeGatingEnabled : defaults.regimeGatingEnabled(),
                    vpinFilterEnabled != null ? vpinFilterEnabled : defaults.vpinFilterEnabled(),
                    vpinHighThreshold != null ? vpinHighThreshold : defaults.vpinHighThreshold(),
                    buyThreshold != null ? buyThreshold : defaults.buyThreshold(),
                    sellThreshold != null ? sellThreshold : defaults.sellThreshold(),
                    confirmationSnapshots != null ? confirmationSnapshots : defaults.confirmationSnapshots(),
                    targetPercent != null ? targetPercent : defaults.targetPercent(),
                    stopLossPercent != null ? stopLossPercent : defaults.stopLossPercent(),
                    commissionRate != null ? commissionRate : defaults.commissionRate());

            if (sizeSplit) {
                candles = withTradeSizeSplit(candles);
            }
            DerivativesHistory derivatives = loadBacktestDerivatives(candles, clampedDays);
            BacktestReport report = new BacktestRunner().run(candles, Config.getTradingInitialCapital(), params, derivatives);
            return ResponseEntity.ok(Map.of(
                    "symbol", Config.getMarketSymbol(),
                    "interval", Config.getBinanceKlineInterval(),
                    "requestedDays", clampedDays,
                    "candleCount", candles.size(),
                    "rangeStart", candles.get(0).openTime(),
                    "rangeEnd", candles.get(candles.size() - 1).closeTime(),
                    "params", params,
                    "derivativesLoaded", !derivatives.isEmpty(),
                    "sizeSplitCandles", BinanceAggTradeArchive.candlesWithSizeSplit(candles),
                    "report", report
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Backtest failed: " + e.getMessage()));
        }
    }
}
