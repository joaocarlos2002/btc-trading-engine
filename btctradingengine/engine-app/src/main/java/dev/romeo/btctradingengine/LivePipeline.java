package dev.romeo.btctradingengine;

import dev.romeo.btctradingengine.adapter.BinanceAdapter;
import dev.romeo.btctradingengine.adapter.BinanceKlineClient;
import dev.romeo.btctradingengine.adapter.CandleAggregator;
import dev.romeo.btctradingengine.adapter.PriceEventBus;
import dev.romeo.btctradingengine.alerting.AlertNotifier;
import dev.romeo.btctradingengine.config.BinanceProperties;
import dev.romeo.btctradingengine.config.FeatureProperties;
import dev.romeo.btctradingengine.config.MarketProperties;
import dev.romeo.btctradingengine.config.TradingProperties;
import dev.romeo.btctradingengine.dashboard.DashboardState;
import dev.romeo.btctradingengine.derivatives.DerivativesHistory;
import dev.romeo.btctradingengine.derivatives.DerivativesPoller;
import dev.romeo.btctradingengine.feature.DerivativesLookup;
import dev.romeo.btctradingengine.feature.FeatureExtractor;
import dev.romeo.btctradingengine.feature.IndicatorPeriods;
import dev.romeo.btctradingengine.feature.OrderBookLookup;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.orderbook.OrderBookHistory;
import dev.romeo.btctradingengine.orderbook.OrderBookPoller;
import dev.romeo.btctradingengine.persistence.DatabaseCandleReader;
import dev.romeo.btctradingengine.persistence.DatabaseWriter;
import dev.romeo.btctradingengine.persistence.JdbcOrderCommandStore;
import dev.romeo.btctradingengine.persistence.TickRetentionJob;
import dev.romeo.btctradingengine.prediction.PredictionSettings;
import dev.romeo.btctradingengine.prediction.RuleBasedPredictor;
import dev.romeo.btctradingengine.trading.BinanceOrderExecutor;
import dev.romeo.btctradingengine.trading.BinanceReconciliationService;
import dev.romeo.btctradingengine.trading.BinanceUserDataStreamClient;
import dev.romeo.btctradingengine.resilience.BinanceResilience;
import dev.romeo.btctradingengine.trading.ConnectivityGuard;
import dev.romeo.btctradingengine.trading.OrderConfirmationManager;
import dev.romeo.btctradingengine.trading.PortfolioManager;
import dev.romeo.btctradingengine.trading.PositionManager;
import dev.romeo.btctradingengine.trading.TradeJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.math.RoundingMode;

/**
 * The live pipeline (issue #77): Binance aggTrade stream -> PriceEventBus -> CandleAggregator ->
 * FeatureExtractor -> RuleBasedPredictor -> PositionManager, plus persistence, derivatives, order book
 * and, in real mode, the exchange checks and reconciliation.
 *
 * <p>Its collaborators are Spring beans (see LivePipelineConfiguration); this class keeps what must
 * happen in order. {@link #run} starts it once the context (and the dashboard) is up, in the same order
 * the manual wiring in Main used; any failure propagates and stops the application. {@link #close} is
 * the orderly shutdown, called when the context closes: stop the feed, drain the order I/O, save the
 * trades, then the writers.
 */
public class LivePipeline implements ApplicationRunner, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(LivePipeline.class);

    /** Collaborators, grouped so the constructor stays readable. */
    public record Components(
            DataSource dataSource,
            DatabaseWriter dbWriter,
            TickRetentionJob tickRetention,
            TradeJournal tradeJournal,
            JdbcOrderCommandStore orderCommands,
            PositionManager positionManager,
            ConnectivityGuard connectivityGuard,
            AlertNotifier alertNotifier,
            DashboardState dashboardState,
            DerivativesHistory derivativesHistory,
            DerivativesPoller derivativesPoller,
            OrderBookHistory orderBookHistory,
            OrderBookPoller orderBookPoller,
            BinanceKlineClient spotKlines,
            BinanceAdapter source,
            PriceEventBus priceEventBus,
            BinanceResilience resilience) {
    }

    private final Components c;
    private final MarketProperties market;
    private final FeatureProperties features;
    private final TradingProperties trading;
    private final BinanceProperties binance;
    private final IndicatorPeriods periods;
    private final PredictionSettings prediction;

    private CandleAggregator aggregator;
    private BinanceUserDataStreamClient userDataStream;
    private OrderConfirmationManager confirmationManager;
    private volatile boolean started;

    public LivePipeline(Components components, MarketProperties market, FeatureProperties features,
                        TradingProperties trading, BinanceProperties binance, IndicatorPeriods periods,
                        PredictionSettings prediction) {
        this.c = components;
        this.market = market;
        this.features = features;
        this.trading = trading;
        this.binance = binance;
        this.periods = periods;
        this.prediction = prediction;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String symbol = market.symbol();

        c.dbWriter().start();
        c.tickRetention().start();

        TradeJournal tradeJournal = c.tradeJournal();

        PositionManager positionManager = c.positionManager();
        DashboardState dashboardState = c.dashboardState();
        dashboardState.attachPositionManager(positionManager);
        positionManager.setConnectivityGuard(c.connectivityGuard());
        positionManager.setAlertNotifier(c.alertNotifier());

        tradeJournal.loadOpenPosition(symbol, trading.targetPercent(), trading.stopLossPercent())
                .ifPresent(positionManager::restoreOpenPosition);

        if (trading.realEnabled()) {
            startRealTrading(symbol);
        }
        positionManager.restoreClosedPositions(tradeJournal.loadClosedPositions(
                symbol, trading.targetPercent(), trading.stopLossPercent(), 500));

        var lastCandle = new Object() { CandleEvent value = null; };

        RuleBasedPredictor predictor = RuleBasedPredictor.withLiveRules(
                p -> {
                    logger.info("SIGNAL: {} | prob_up={} | confidence={} | price={} | scores={}",
                            p.signal(),
                            p.probabilityUp().setScale(3, RoundingMode.HALF_UP),
                            p.confidence().setScale(3, RoundingMode.HALF_UP),
                            p.price(),
                            p.reason());
                    dashboardState.onPrediction(p);
                    if (lastCandle.value != null) {
                        positionManager.processPrediction(p, lastCandle.value);
                        dashboardState.refreshPositions();
                    }
                },
                periods, prediction);

        if (c.derivativesPoller() != null) {
            // Before the warmup below, so the warmup candles get funding and basis too
            c.derivativesPoller().seed(market.historyCandles());
        }

        FeatureExtractor featureExtractor = new FeatureExtractor(
                periods,
                c.derivativesHistory() != null ? c.derivativesHistory() : DerivativesLookup.NONE,
                c.orderBookHistory() != null ? c.orderBookHistory() : OrderBookLookup.NONE,
                f -> {
                    dashboardState.onFeatures(f);
                    positionManager.updateAtr(f.atrValue());
                    predictor.onEvent(f);
                });

        warmUp(featureExtractor);

        // Market data and execution are separate venues (issue #76): mainnet prices, testnet orders by default
        logger.info("Market data from {} / {}; execution on {}",
                market.dataWsUrl(), market.dataRestUrl(), binance.restUrl());
        BinanceAdapter source = c.source();
        source.setStatusListener(status -> {
            c.connectivityGuard().onMarketDataStatus(status);
            if ("failed".equals(status)) {
                c.alertNotifier().alert("Market data stream reconnection exhausted its retries and gave up; "
                        + "the bot may be blind to price movements until it recovers");
            }
        });
        aggregator = new CandleAggregator(
                market.interval(),
                candle -> {
                    lastCandle.value = candle;
                    dashboardState.onCandle(candle);
                    c.dbWriter().onEvent(candle);
                    featureExtractor.onEvent(candle);
                }, features.largeTradeNotional());

        PriceEventBus priceEventBus = c.priceEventBus();
        // Aggregator, DB writer and trading path must not lose ticks: bounded queues that block the
        // reader briefly when full. The dashboard only needs the latest tick (issue #85).
        priceEventBus.subscribe(aggregator);
        priceEventBus.subscribe(c.dbWriter());
        priceEventBus.subscribe(event -> {
            c.connectivityGuard().recordPriceEvent();
            positionManager.processPriceEvent(event);
            dashboardState.refreshPositions();
        });
        priceEventBus.subscribeLatest(dashboardState);

        if (c.derivativesPoller() != null) {
            c.derivativesPoller().start();
        }
        if (c.orderBookPoller() != null) {
            c.orderBookPoller().start();
        }
        aggregator.start();
        started = true;
        source.start(priceEventBus);
        logger.info("Live pipeline started for {} ({} candles)", symbol, market.binanceInterval());
    }

    private void startRealTrading(String symbol) {
        PositionManager positionManager = c.positionManager();
        AlertNotifier alertNotifier = c.alertNotifier();
        // Shared REST policies (issue #100): per-request retries live there; exit retries across ticks stay in
        // PositionManager, and an open order breaker blocks entries through the guard, never exits
        BinanceOrderExecutor executor = new BinanceOrderExecutor(binance.apiKey(), binance.apiSecret(),
                binance.restUrl(), c.resilience());
        c.connectivityGuard().setExchangeHealth(executor::circuitOpenReason);
        BinanceOrderExecutor.BalanceResult balance = executor.getBalance("USDT");
        if (!balance.success() || balance.total().compareTo(BigDecimal.ZERO) <= 0) {
            alertNotifier.alert("Startup aborted: could not validate positive USDT balance: " + balance.error());
            throw new IllegalStateException("Could not validate positive USDT balance: " + balance.error());
        }

        BinanceOrderExecutor.SymbolFilters symbolFilters = executor.getSymbolFilters(symbol);
        if (symbolFilters == null || symbolFilters.minQty().compareTo(BigDecimal.ZERO) <= 0
                || symbolFilters.stepSize().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Could not validate exchange symbol filters for " + symbol);
        }
        logger.info("Symbol filters validated for {}: minNotional={} minQty={} maxQty={} stepSize={}",
                symbol, symbolFilters.minNotional(), symbolFilters.minQty(),
                symbolFilters.maxQty(), symbolFilters.stepSize());

        PortfolioManager portfolio = new PortfolioManager(balance.total(), trading.maxDrawdownPercent());
        // Equity counts the base asset too (issue #81); valued once the first price arrives
        String baseAsset = symbol.replaceFirst("USDT$", "");
        BinanceOrderExecutor.BalanceResult baseBalance = executor.getBalance(baseAsset);
        if (baseBalance.success()) {
            portfolio.setInitialBaseQuantity(baseBalance.total());
        } else {
            logger.warn("Could not read initial {} balance; initial equity counts USDT only: {}",
                    baseAsset, baseBalance.error());
        }
        positionManager.setRealTradingMode(executor, portfolio, symbol);
        // Before reconciliation, which checks or re-places the OCO of a persisted position
        positionManager.setOcoProtection(trading.ocoEnabled(), trading.ocoStopLimitOffsetPercent());
        positionManager.setOrderCommandStore(c.orderCommands());
        logger.warn("REAL TRADING ENABLED for {} with validated USDT balance={}", symbol, balance.total());
        if (!binance.testnetEndpoint()) {
            logger.warn("!!! MAINNET TRADING CONFIRMED (binance.rest.url={}) - REAL MONEY IS AT RISK !!!",
                    binance.restUrl());
        }

        BinanceReconciliationService reconciliation = new BinanceReconciliationService(
                executor, positionManager, trading.targetPercent(), trading.stopLossPercent(), alertNotifier);
        reconciliation.setOrderCommandStore(c.orderCommands());
        var reconcileResult = reconciliation.reconcile(symbol);
        logger.info("Reconciliation result: {} | orders_found={} | position_restored={}",
                reconcileResult.message(),
                reconcileResult.ordersFound(),
                reconcileResult.restoredPosition() != null);
        if (!reconcileResult.success()) {
            alertNotifier.alert("Startup aborted: Binance reconciliation failed: " + reconcileResult.message());
            throw new IllegalStateException("Trading disabled: Binance reconciliation failed: "
                    + reconcileResult.message());
        }
        positionManager.markReconciliationComplete();

        confirmationManager = new OrderConfirmationManager(executor);
        positionManager.setOrderConfirmationManager(confirmationManager);
        c.dashboardState().attachOrderConfirmationManager(confirmationManager);

        userDataStream = new BinanceUserDataStreamClient(binance.apiKey(), binance.apiSecret(),
                binance.testnetEndpoint(), binance.maxRetries(), c.resilience().settings()
                        .backoff(Duration.ofMillis(binance.initialBackoffMs()), Duration.ofMillis(binance.maxBackoffMs())));
        userDataStream.setExecutionReportListener(report -> {
            logger.debug("Execution report received: orderId={} status={}", report.orderId(), report.orderStatus());
            confirmationManager.processExecutionReport(report);
        });
        userDataStream.setConnectionStatusListener(status -> {
            logger.info("User Data Stream: {}", status);
            c.connectivityGuard().onUserDataStreamStatus(status);
            if ("failed".equals(status)) {
                alertNotifier.alert("User Data Stream reconnection exhausted its retries and gave up; "
                        + "order fill notifications may be delayed until the next REST poll");
            }
        });

        if (!userDataStream.connect()) {
            logger.warn("Failed to connect User Data Stream; will use polling fallback");
            alertNotifier.alert("Failed to connect User Data Stream on startup; falling back to REST polling for fills");
        }
    }

    private void warmUp(FeatureExtractor featureExtractor) {
        DashboardState dashboardState = c.dashboardState();
        try {
            var history = c.spotKlines().loadClosedCandles(
                    market.symbol(), market.binanceInterval(), market.historyCandles());
            history.forEach(candle -> {
                dashboardState.onCandle(candle);
                featureExtractor.warmUp(candle, dashboardState::onFeatures);
            });
            logger.info("Loaded {} closed candles from Binance for indicator warmup", history.size());
        } catch (Exception e) {
            logger.warn("Could not load Binance candle history; trying database fallback: {}", e.getMessage());
            try {
                var history = new DatabaseCandleReader(c.dataSource())
                        .loadRecentClosedCandles(market.symbol(), market.historyCandles());
                history.forEach(candle -> {
                    dashboardState.onCandle(candle);
                    featureExtractor.warmUp(candle, dashboardState::onFeatures);
                });
                logger.info("Loaded {} closed candles from database for indicator warmup", history.size());
            } catch (Exception databaseException) {
                logger.warn("Could not load candle history from database; starting live without warmup: {}",
                        databaseException.getMessage());
            }
        }
    }

    /** Called when the application context closes. */
    @Override
    public void close() {
        if (started) {
            logger.info("Shutdown initiated - saving trades...");
            c.source().stop();
            c.priceEventBus().close();
            aggregator.stop();
            if (c.derivativesPoller() != null) {
                c.derivativesPoller().stop();
            }
            if (c.orderBookPoller() != null) {
                c.orderBookPoller().stop();
            }
        }
        if (userDataStream != null) {
            userDataStream.disconnect();
        }
        if (confirmationManager != null) {
            confirmationManager.shutdown();
        }

        // Queued order I/O applies its results before the trades are saved
        PositionManager positionManager = c.positionManager();
        positionManager.shutdown();

        if (started) {
            String symbol = market.symbol();
            positionManager.getClosedPositions().forEach(pos -> c.tradeJournal().recordTrade(pos, symbol));
            logger.info("Trades saved: {}", positionManager.getTotalTrades());
        }

        c.tickRetention().close();
        c.dbWriter().stop();
        c.connectivityGuard().shutdown();
    }
}
