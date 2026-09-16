package dev.romeo.btctradingengine;

import dev.romeo.btctradingengine.adapter.*;
import dev.romeo.btctradingengine.alerting.AlertNotifier;
import dev.romeo.btctradingengine.alerting.DiscordAlertNotifier;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.dashboard.DashboardApplication;
import dev.romeo.btctradingengine.dashboard.DashboardState;
import dev.romeo.btctradingengine.derivatives.BinanceFuturesClient;
import dev.romeo.btctradingengine.derivatives.DerivativesHistory;
import dev.romeo.btctradingengine.derivatives.DerivativesPoller;
import dev.romeo.btctradingengine.feature.DerivativesLookup;
import dev.romeo.btctradingengine.feature.FeatureExtractor;
import dev.romeo.btctradingengine.feature.IndicatorPeriods;
import dev.romeo.btctradingengine.feature.OrderBookLookup;
import dev.romeo.btctradingengine.orderbook.BinanceDepthClient;
import dev.romeo.btctradingengine.orderbook.OrderBookHistory;
import dev.romeo.btctradingengine.orderbook.OrderBookPoller;
import dev.romeo.btctradingengine.persistence.DerivativesSnapshotWriter;
import dev.romeo.btctradingengine.persistence.OrderBookSnapshotWriter;
import dev.romeo.btctradingengine.persistence.DataSourceManager;
import dev.romeo.btctradingengine.persistence.DatabaseCandleReader;
import dev.romeo.btctradingengine.persistence.DatabaseInitializer;
import dev.romeo.btctradingengine.persistence.TickRetentionJob;
import dev.romeo.btctradingengine.persistence.DatabaseWriter;
import dev.romeo.btctradingengine.persistence.JdbcOrderCommandStore;
import dev.romeo.btctradingengine.prediction.RuleBasedPredictor;
import dev.romeo.btctradingengine.trading.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    static void main(String[] args) {
        try {
            Config.validate();
            ConfigurableApplicationContext dashboardContext = SpringApplication.run(DashboardApplication.class, args);
            DashboardState dashboardState = dashboardContext.getBean(DashboardState.class);
            initializeDatabase();

            DatabaseWriter dbWriter = new DatabaseWriter(DataSourceManager.getDataSource());
            dbWriter.start();
            TickRetentionJob tickRetention = TickRetentionJob.create(DataSourceManager.getDataSource(), Config.getTicksRetentionDays(),
                    Config.getTicksRetentionBatchSize(), java.time.Duration.ofMinutes(Math.max(1, Config.getTicksRetentionIntervalMinutes())));
            tickRetention.start();

            TradeJournal tradeJournal = new TradeJournal(DataSourceManager.getDataSource());
            tradeJournal.createTableIfNotExists();
            tradeJournal.createExecutionLogTableIfNotExists();
            JdbcOrderCommandStore orderCommands = new JdbcOrderCommandStore(DataSourceManager::getDataSource);
            orderCommands.createTableIfNotExists();

            PositionManager positionManager = new PositionManager(
                    Config.getTradingTargetPercent(),
                    Config.getTradingStopLossPercent() ,
                    position -> tradeJournal.recordTrade(position, Config.getMarketSymbol()),
                    event -> tradeJournal.recordExecutionLog(event, Config.getMarketSymbol())
            );
            positionManager.setAllowShort(Config.isShortSellingAllowed());
            positionManager.setPositionSizing(Config.getPositionSizingStrategy());
            dashboardState.attachPositionManager(positionManager);

            ConnectivityGuard connectivityGuard = new ConnectivityGuard(
                    java.time.Duration.ofSeconds(Config.getMaxDataStalenessSeconds()),
                    Config.isRealTradingEnabled());
            positionManager.setConnectivityGuard(connectivityGuard);

            AlertNotifier alertNotifier = new DiscordAlertNotifier(Config.getAlertDiscordWebhookUrl());
            positionManager.setAlertNotifier(alertNotifier);

            tradeJournal.loadOpenPosition(
                    Config.getMarketSymbol(),
                    Config.getTradingTargetPercent(),
                    Config.getTradingStopLossPercent()
            ).ifPresent(positionManager::restoreOpenPosition);

            if (Config.isRealTradingEnabled()) {
                BinanceOrderExecutor executor = new BinanceOrderExecutor(
                    Config.getBinanceApiKey(), Config.getBinanceApiSecret(), Config.getBinanceRestUrl(),
                    Config.getBinanceMaxRetries(), Config.getBinanceInitialBackoffMs(), Config.getBinanceMaxBackoffMs());
                BinanceOrderExecutor.BalanceResult balance = executor.getBalance("USDT");
                if (!balance.success() || balance.total().compareTo(BigDecimal.ZERO) <= 0) {
                    alertNotifier.alert("Startup aborted: could not validate positive USDT balance: " + balance.error());
                    throw new IllegalStateException("Could not validate positive USDT balance: " + balance.error());
                }

                BinanceOrderExecutor.SymbolFilters symbolFilters = executor.getSymbolFilters(Config.getMarketSymbol());
                if (symbolFilters == null || symbolFilters.minQty().compareTo(BigDecimal.ZERO) <= 0
                        || symbolFilters.stepSize().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalStateException(
                            "Could not validate exchange symbol filters for " + Config.getMarketSymbol());
                }
                logger.info("Symbol filters validated for {}: minNotional={} minQty={} maxQty={} stepSize={}",
                        Config.getMarketSymbol(), symbolFilters.minNotional(), symbolFilters.minQty(),
                        symbolFilters.maxQty(), symbolFilters.stepSize());

                PortfolioManager portfolio = new PortfolioManager(
                    balance.total(),
                    Config.getTradingMaxDrawdownPercent());
                // Equity counts the base asset too (issue #81); valued once the first price arrives
                String baseAsset = Config.getMarketSymbol().replaceFirst("USDT$", "");
                BinanceOrderExecutor.BalanceResult baseBalance = executor.getBalance(baseAsset);
                if (baseBalance.success()) {
                    portfolio.setInitialBaseQuantity(baseBalance.total());
                } else {
                    logger.warn("Could not read initial {} balance; initial equity counts USDT only: {}",
                        baseAsset, baseBalance.error());
                }
                positionManager.setRealTradingMode(
                    executor,
                    portfolio,
                    Config.getMarketSymbol());
                // Before reconciliation, which checks or re-places the OCO of a persisted position
                positionManager.setOcoProtection(Config.isOcoProtectionEnabled(), Config.getOcoStopLimitOffsetPercent());
                positionManager.setOrderCommandStore(orderCommands);
                logger.warn("REAL TRADING ENABLED for {} with validated USDT balance={}",
                    Config.getMarketSymbol(), balance.total());
                if (!Config.isBinanceTestnetEndpoint()) {
                    logger.warn("!!! MAINNET TRADING CONFIRMED (binance.rest.url={}) - REAL MONEY IS AT RISK !!!",
                        Config.getBinanceRestUrl());
                }

                BinanceReconciliationService reconciliation = new BinanceReconciliationService(
                    executor,
                    positionManager,
                    Config.getTradingTargetPercent(),
                    Config.getTradingStopLossPercent(),
                    alertNotifier);
                reconciliation.setOrderCommandStore(orderCommands);
                var reconcileResult = reconciliation.reconcile(Config.getMarketSymbol());
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

                OrderConfirmationManager confirmationManager = new OrderConfirmationManager(executor);
                positionManager.setOrderConfirmationManager(confirmationManager);
                dashboardState.attachOrderConfirmationManager(confirmationManager);

                BinanceUserDataStreamClient userDataStream =
                        getBinanceUserDataStreamClient(confirmationManager, connectivityGuard, alertNotifier);

                if (!userDataStream.connect()) {
                    logger.warn("Failed to connect User Data Stream; will use polling fallback");
                    alertNotifier.alert("Failed to connect User Data Stream on startup; falling back to REST polling for fills");
                }

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    userDataStream.disconnect();
                    confirmationManager.shutdown();
                }));
                }
                    positionManager.restoreClosedPositions(tradeJournal.loadClosedPositions(
                        Config.getMarketSymbol(),
                        Config.getTradingTargetPercent(),
                        Config.getTradingStopLossPercent(),
                        500
                    ));

            var lastCandle = new Object() { dev.romeo.btctradingengine.model.CandleEvent value = null; };

            RuleBasedPredictor predictor = RuleBasedPredictor.withLiveRules(
                    prediction -> {
                        logger.info("SIGNAL: {} | prob_up={} | confidence={} | price={} | scores={}",
                                prediction.signal(),
                                prediction.probabilityUp().setScale(3, java.math.RoundingMode.HALF_UP),
                                prediction.confidence().setScale(3, java.math.RoundingMode.HALF_UP),
                            prediction.price(),
                            prediction.reason());
                        dashboardState.onPrediction(prediction);
                        if (lastCandle.value != null) {
                            positionManager.processPrediction(prediction, lastCandle.value);
                            dashboardState.refreshPositions();
                        }
                    },
                    Config.indicatorPeriods(),
                    Config.predictionSettings()
            );

            DerivativesHistory derivativesHistory = Config.isDerivativesEnabled() ? DerivativesHistory.forLive(
                    java.time.Duration.ofSeconds(Config.getDerivativesStaleSeconds()), java.time.Duration.ofMinutes(Config.getOpenInterestChangeMinutes()),
                    Config.getMarketInterval(), Config.getHistoryCandles()) : null;
            DerivativesPoller derivativesPoller = derivativesHistory == null ? null : new DerivativesPoller(
                    new BinanceFuturesClient(Config.getBinanceFuturesRestUrl()),
                    BinanceKlineClient.usdmFutures(Config.getBinanceFuturesRestUrl(), Config.getKlineCacheMaxEntries(), Config.getKlineCacheMaxCandles()),
                    derivativesHistory,
                    new DerivativesSnapshotWriter(DataSourceManager.getDataSource()),
                    Config.getMarketSymbol(),
                    Config.getBinanceKlineInterval(),
                    Config.getMarketInterval(), Config.getDerivativesBasisPollSeconds(), Config.getDerivativesPollSeconds());
            if (derivativesPoller != null) {
                // Before the warmup below, so the warmup candles get funding and basis too
                derivativesPoller.seed(Config.getHistoryCandles());
            }

            // Only the candles closing while the bot runs can have snapshots, so an hour of retention is plenty
            OrderBookHistory orderBookHistory = Config.isOrderBookEnabled()
                    ? new OrderBookHistory(java.time.Duration.ofHours(1)) : null;
            OrderBookPoller orderBookPoller = orderBookHistory == null ? null : new OrderBookPoller(
                    new BinanceDepthClient(Config.getOrderBookRestUrl()),
                    orderBookHistory,
                    new OrderBookSnapshotWriter(DataSourceManager.getDataSource()),
                    Config.getMarketSymbol(),
                    Config.getOrderBookDepthLevels(), Config.getOrderBookPollSeconds());

            FeatureExtractor featureExtractor = new FeatureExtractor(
                    Config.indicatorPeriods(),
                    derivativesHistory != null ? derivativesHistory : DerivativesLookup.NONE,
                    orderBookHistory != null ? orderBookHistory : OrderBookLookup.NONE,
                    features -> {
                        dashboardState.onFeatures(features);
                        positionManager.updateAtr(features.atrValue());
                        predictor.onEvent(features);
                    }
            );

            try {
                var history = new BinanceKlineClient(Config.getMarketDataRestUrl(), Config.getKlineCacheMaxEntries(), Config.getKlineCacheMaxCandles()).loadClosedCandles(
                        Config.getMarketSymbol(),
                        Config.getBinanceKlineInterval(),
                        Config.getHistoryCandles());
                history.forEach(candle -> {
                    dashboardState.onCandle(candle);
                    featureExtractor.warmUp(candle, dashboardState::onFeatures);
                });
                logger.info("Loaded {} closed candles from Binance for indicator warmup", history.size());
            } catch (Exception e) {
                logger.warn("Could not load Binance candle history; trying database fallback: {}", e.getMessage());
                try {
                    var history = new DatabaseCandleReader(DataSourceManager.getDataSource()).loadRecentClosedCandles(
                            Config.getMarketSymbol(), Config.getHistoryCandles());
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

            // Market data and execution are separate venues (issue #76): mainnet prices, testnet orders by default
            logger.info("Market data from {} / {}; execution on {}",
                    Config.getMarketDataWsUrl(), Config.getMarketDataRestUrl(), Config.getBinanceRestUrl());
            BinanceAdapter source = new BinanceAdapter(Config.getMarketDataWsUrl(), Config.getMarketSymbol(),
                    Config.getBinanceMaxRetries(), Config.getBinanceInitialBackoffMs(), Config.getBinanceMaxBackoffMs());
            source.setStatusListener(status -> {
                connectivityGuard.onMarketDataStatus(status);
                if ("failed".equals(status)) {
                    alertNotifier.alert("Market data stream reconnection exhausted its retries and gave up; "
                            + "the bot may be blind to price movements until it recovers");
                }
            });
            CandleAggregator aggregator = new CandleAggregator(
                    Config.getMarketInterval(),
                    candle -> {
                        lastCandle.value = candle;
                        dashboardState.onCandle(candle);
                        dbWriter.onEvent(candle);
                        featureExtractor.onEvent(candle);
                    }, Config.getLargeTradeNotional());

            PriceEventBus priceEventBus = new PriceEventBus(Config.getPriceBusQueueCapacity(), Config.getPriceBusBlockTimeoutMs());
            // Aggregator, DB writer and trading path must not lose ticks: bounded queues that block the
            // reader briefly when full. The dashboard only needs the latest tick (issue #85).
            priceEventBus.subscribe(aggregator);
            priceEventBus.subscribe(dbWriter);
            priceEventBus.subscribe(event -> {
                connectivityGuard.recordPriceEvent();
                positionManager.processPriceEvent(event);
                dashboardState.refreshPositions();
            });
            priceEventBus.subscribeLatest(dashboardState);

            if (derivativesPoller != null) {
                derivativesPoller.start();
            }
            if (orderBookPoller != null) {
                orderBookPoller.start();
            }
            aggregator.start();
            source.start(priceEventBus);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutdown initiated - saving trades...");

                source.stop();
                priceEventBus.close();
                aggregator.stop();
                if (derivativesPoller != null) {
                    derivativesPoller.stop();
                }
                if (orderBookPoller != null) {
                    orderBookPoller.stop();
                }

                // Queued order I/O applies its results before the trades are saved
                positionManager.shutdown();

                String symbol = Config.getMarketSymbol();
                positionManager.getClosedPositions().forEach(pos ->
                    tradeJournal.recordTrade(pos, symbol)
                );

                logger.info("Trades saved: {}", positionManager.getTotalTrades());

                tickRetention.close();
                dbWriter.stop();
                DataSourceManager.close();
                dashboardState.close();
                connectivityGuard.shutdown();
                dashboardContext.close();
            }));

            new CountDownLatch(1).await();

        } catch (Exception e) {
            logger.error("Application failed", e);
            System.exit(1);
        }
    }

    private static BinanceUserDataStreamClient getBinanceUserDataStreamClient(
            OrderConfirmationManager confirmationManager, ConnectivityGuard connectivityGuard, AlertNotifier alertNotifier) {
        BinanceUserDataStreamClient userDataStream = new BinanceUserDataStreamClient(
                Config.getBinanceApiKey(), Config.getBinanceApiSecret(), Config.isBinanceTestnetEndpoint(),
                Config.getBinanceMaxRetries(), Config.getBinanceInitialBackoffMs(), Config.getBinanceMaxBackoffMs());
        userDataStream.setExecutionReportListener(
            report -> {
                logger.debug("Execution report received: orderId={} status={}", report.orderId(), report.orderStatus());
                confirmationManager.processExecutionReport(report);
            });
        userDataStream.setConnectionStatusListener(
            status -> {
                logger.info("User Data Stream: {}", status);
                connectivityGuard.onUserDataStreamStatus(status);
                if ("failed".equals(status)) {
                    alertNotifier.alert("User Data Stream reconnection exhausted its retries and gave up; "
                            + "order fill notifications may be delayed until the next REST poll");
                }
            });
        return userDataStream;
    }

    private static void initializeDatabase() {
        logger.info("Initializing database...");
        try {
            DatabaseInitializer.initializeSchema(DataSourceManager.getDataSource());
            logger.info("Database initialization complete");
        } catch (Exception e) {
            logger.error("Failed to initialize database", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }
}
