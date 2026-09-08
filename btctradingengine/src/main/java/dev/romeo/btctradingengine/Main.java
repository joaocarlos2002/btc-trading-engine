package dev.romeo.btctradingengine;

import dev.romeo.btctradingengine.adapter.BinanceAdapter;
import dev.romeo.btctradingengine.adapter.BinanceKlineClient;
import dev.romeo.btctradingengine.adapter.CandleAggregator;
import dev.romeo.btctradingengine.adapter.MarketDataSource;
import dev.romeo.btctradingengine.adapter.PriceEventBus;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.dashboard.DashboardApplication;
import dev.romeo.btctradingengine.dashboard.DashboardState;
import dev.romeo.btctradingengine.feature.FeatureExtractor;
import dev.romeo.btctradingengine.persistence.DatabaseInitializer;
import dev.romeo.btctradingengine.persistence.DatabaseCandleReader;
import dev.romeo.btctradingengine.persistence.DatabaseWriter;
import dev.romeo.btctradingengine.persistence.DataSourceManager;
import dev.romeo.btctradingengine.prediction.RuleBasedPredictor;
import dev.romeo.btctradingengine.prediction.rules.AtrRule;
import dev.romeo.btctradingengine.prediction.rules.MacdRule;
import dev.romeo.btctradingengine.prediction.rules.RsiRule;
import dev.romeo.btctradingengine.prediction.rules.SmaMomentumRule;
import dev.romeo.btctradingengine.prediction.rules.VolatilityRule;
import dev.romeo.btctradingengine.trading.PositionManager;
import dev.romeo.btctradingengine.trading.BinanceOrderExecutor;
import dev.romeo.btctradingengine.trading.PortfolioManager;
import dev.romeo.btctradingengine.trading.TradeJournal;
import dev.romeo.btctradingengine.trading.BinanceReconciliationService;
import dev.romeo.btctradingengine.trading.BinanceUserDataStreamClient;
import dev.romeo.btctradingengine.trading.OrderConfirmationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.SpringApplication;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws InterruptedException {
        try {
            Config.validate();
            ConfigurableApplicationContext dashboardContext = SpringApplication.run(DashboardApplication.class, args);
            DashboardState dashboardState = dashboardContext.getBean(DashboardState.class);
            initializeDatabase();

            DatabaseWriter dbWriter = new DatabaseWriter();
            dbWriter.start();

            TradeJournal tradeJournal = new TradeJournal();
            tradeJournal.createTableIfNotExists();
            tradeJournal.createExecutionLogTableIfNotExists();

            PositionManager positionManager = new PositionManager(
                    Config.getTradingTargetPercent(),
                    Config.getTradingStopLossPercent() ,
                    position -> tradeJournal.recordTrade(position, Config.getMarketSymbol()),
                    event -> tradeJournal.recordExecutionLog(event, Config.getMarketSymbol())
            );
            dashboardState.attachPositionManager(positionManager);
                if (Config.isRealTradingEnabled()) {
                BinanceOrderExecutor executor = new BinanceOrderExecutor(
                    Config.getBinanceApiKey(), Config.getBinanceApiSecret());
                BinanceOrderExecutor.BalanceResult balance = executor.getBalance("USDT");
                if (!balance.success() || balance.total().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalStateException("Could not validate positive USDT balance: " + balance.error());
                }
                PortfolioManager portfolio = new PortfolioManager(
                    balance.total(),
                    Config.getTradingMaxDrawdownPercent());
                positionManager.setRealTradingMode(
                    executor,
                    portfolio,
                    Config.getMarketSymbol());
                logger.warn("REAL TRADING ENABLED for {} with validated USDT balance={}",
                    Config.getMarketSymbol(), balance.total());

                BinanceReconciliationService reconciliation = new BinanceReconciliationService(
                    executor,
                    positionManager,
                    Config.getTradingTargetPercent(),
                    Config.getTradingStopLossPercent());
                var reconcileResult = reconciliation.reconcile(Config.getMarketSymbol());
                logger.info("Reconciliation result: {} | orders_found={} | position_restored={}",
                    reconcileResult.message(),
                    reconcileResult.ordersFound(),
                    reconcileResult.restoredPosition() != null);

                OrderConfirmationManager confirmationManager = new OrderConfirmationManager(executor);
                positionManager.setOrderConfirmationManager(confirmationManager);
                dashboardState.attachOrderConfirmationManager(confirmationManager);

                BinanceUserDataStreamClient userDataStream = new BinanceUserDataStreamClient(Config.getBinanceApiKey());
                userDataStream.setExecutionReportListener(
                    report -> {
                        logger.debug("Execution report received: orderId={} status={}", report.orderId(), report.orderStatus());
                        confirmationManager.processExecutionReport(report);
                    });
                userDataStream.setConnectionStatusListener(
                    status -> logger.info("User Data Stream: {}", status));

                if (!userDataStream.connect()) {
                    logger.warn("âš  Failed to connect User Data Stream; will use polling fallback");
                }

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    userDataStream.disconnect();
                    confirmationManager.shutdown();
                }));
                }
                if (!Config.isRealTradingEnabled()) {
                    tradeJournal.loadOpenPosition(
                            Config.getMarketSymbol(),
                            Config.getTradingTargetPercent(),
                            Config.getTradingStopLossPercent()
                    ).ifPresent(positionManager::restoreOpenPosition);
                }
                    positionManager.restoreClosedPositions(tradeJournal.loadClosedPositions(
                        Config.getMarketSymbol(),
                        Config.getTradingTargetPercent(),
                        Config.getTradingStopLossPercent(),
                        500
                    ));

            // Store last candle para usar no predictor â†’ positionManager
            var lastCandle = new Object() { dev.romeo.btctradingengine.model.CandleEvent value = null; };

            RuleBasedPredictor predictor = new RuleBasedPredictor(
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
                    }
            );
            predictor.addRule(new RsiRule());
            predictor.addRule(new SmaMomentumRule());
            predictor.addRule(new MacdRule());
            predictor.addRule(new AtrRule());
            predictor.addRule(new VolatilityRule());

            FeatureExtractor featureExtractor = new FeatureExtractor(
                    Config.getSmaPeriod(),
                    Config.getEmaPeriod(),
                    Config.getRsiPeriod(),
                    features -> {
                        dashboardState.onFeatures(features);
                        predictor.onEvent(features);
                    }
            );

            try {
                var history = new BinanceKlineClient().loadClosedCandles(
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
                    var history = new DatabaseCandleReader().loadRecentClosedCandles(
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

            MarketDataSource source = new BinanceAdapter();
            CandleAggregator aggregator = new CandleAggregator(
                    Config.getMarketInterval(),
                    candle -> {
                        lastCandle.value = candle;
                        dashboardState.onCandle(candle);
                        dbWriter.onEvent(candle);
                        featureExtractor.onEvent(candle);
                    });

            PriceEventBus priceEventBus = new PriceEventBus();
            priceEventBus.subscribe(aggregator);
            priceEventBus.subscribe(dbWriter);
            priceEventBus.subscribe(event -> {
                positionManager.processPriceEvent(event);
                dashboardState.refreshPositions();
            });
            priceEventBus.subscribeLatest(dashboardState);

            aggregator.start();
            source.start(priceEventBus);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutdown initiated - saving trades...");

                source.stop();
                priceEventBus.close();
                aggregator.stop();

                String symbol = Config.getMarketSymbol();
                positionManager.getClosedPositions().forEach(pos ->
                    tradeJournal.recordTrade(pos, symbol)
                );

                logger.info("âœ“ Trades saved: {}", positionManager.getTotalTrades());

                dbWriter.stop();
                DataSourceManager.close();
                dashboardState.close();
                dashboardContext.close();
            }));

            new CountDownLatch(1).await();

        } catch (Exception e) {
            logger.error("Application failed", e);
            System.exit(1);
        }
    }

    private static void initializeDatabase() {
        logger.info("Initializing database...");
        try {
            DatabaseInitializer.initializeSchema();
            logger.info("Database initialization complete");
        } catch (Exception e) {
            logger.error("Failed to initialize database", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }
}
