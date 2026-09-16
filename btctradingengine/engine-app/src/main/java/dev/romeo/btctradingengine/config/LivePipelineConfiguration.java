package dev.romeo.btctradingengine.config;

import dev.romeo.btctradingengine.LivePipeline;
import dev.romeo.btctradingengine.adapter.BinanceAdapter;
import dev.romeo.btctradingengine.adapter.BinanceKlineClient;
import dev.romeo.btctradingengine.adapter.PriceEventBus;
import dev.romeo.btctradingengine.alerting.AlertNotifier;
import dev.romeo.btctradingengine.alerting.DiscordAlertNotifier;
import dev.romeo.btctradingengine.dashboard.DashboardState;
import dev.romeo.btctradingengine.derivatives.BinanceFuturesClient;
import dev.romeo.btctradingengine.derivatives.DerivativesHistory;
import dev.romeo.btctradingengine.derivatives.DerivativesPoller;
import dev.romeo.btctradingengine.feature.IndicatorPeriods;
import dev.romeo.btctradingengine.orderbook.BinanceDepthClient;
import dev.romeo.btctradingengine.orderbook.OrderBookHistory;
import dev.romeo.btctradingengine.orderbook.OrderBookPoller;
import dev.romeo.btctradingengine.persistence.DatabaseWriter;
import dev.romeo.btctradingengine.persistence.DerivativesSnapshotWriter;
import dev.romeo.btctradingengine.persistence.JdbcOrderCommandStore;
import dev.romeo.btctradingengine.persistence.OrderBookSnapshotWriter;
import dev.romeo.btctradingengine.persistence.TickRetentionJob;
import dev.romeo.btctradingengine.prediction.PredictionSettings;
import dev.romeo.btctradingengine.resilience.BinanceResilience;
import dev.romeo.btctradingengine.trading.ConnectivityGuard;
import dev.romeo.btctradingengine.trading.PositionManager;
import dev.romeo.btctradingengine.trading.TradeJournal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * Beans of the live pipeline (issues #101 and #77), replacing the manual wiring Main had. On by
 * default; {@code engine.pipeline.enabled=false} leaves only the dashboard and the backtests, which is
 * what the web tests use.
 *
 * <p>Every bean here has {@code destroyMethod = ""}: shutdown order matters (stop the feed, drain the
 * order I/O, save the trades, then the writers), so {@link LivePipeline#close()} does it explicitly
 * instead of leaving it to Spring's reverse creation order.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "engine.pipeline.enabled", havingValue = "true", matchIfMissing = true)
@DependsOn("startupSettingsValidator")
public class LivePipelineConfiguration {

    @Bean(destroyMethod = "")
    DatabaseWriter databaseWriter(DataSource dataSource) {
        return new DatabaseWriter(dataSource);
    }

    @Bean(destroyMethod = "")
    TickRetentionJob tickRetentionJob(DataSource dataSource, DbProperties db) {
        return TickRetentionJob.create(dataSource, db.ticksRetentionDays(), db.ticksRetentionBatchSize(),
                db.ticksRetentionInterval());
    }

    @Bean
    TradeJournal tradeJournal(DataSource dataSource) {
        return new TradeJournal(dataSource);
    }

    @Bean
    JdbcOrderCommandStore orderCommandStore(DataSource dataSource) {
        return new JdbcOrderCommandStore(() -> dataSource);
    }

    @Bean
    AlertNotifier alertNotifier(AlertProperties alert) {
        return new DiscordAlertNotifier(alert.discordWebhookUrl());
    }

    @Bean(destroyMethod = "")
    ConnectivityGuard connectivityGuard(TradingProperties trading) {
        return new ConnectivityGuard(trading.maxDataStaleness(), trading.realEnabled());
    }

    @Bean(destroyMethod = "")
    PositionManager positionManager(TradingProperties trading, MarketProperties market, TradeJournal tradeJournal) {
        String symbol = market.symbol();
        PositionManager positionManager = new PositionManager(
                trading.targetPercent(),
                trading.stopLossPercent(),
                position -> tradeJournal.recordTrade(position, symbol),
                event -> tradeJournal.recordExecutionLog(event, symbol));
        positionManager.setAllowShort(trading.allowShort());
        positionManager.setPositionSizing(StartupSettingsValidator.sizingStrategy(trading));
        return positionManager;
    }

    @Bean
    @ConditionalOnProperty(name = "derivatives.enabled", havingValue = "true")
    DerivativesHistory derivativesHistory(DerivativesProperties derivatives, MarketProperties market) {
        return DerivativesHistory.forLive(derivatives.staleAfter(), derivatives.openInterestChangeWindow(),
                market.interval(), market.historyCandles());
    }

    @Bean(destroyMethod = "")
    @ConditionalOnProperty(name = "derivatives.enabled", havingValue = "true")
    DerivativesPoller derivativesPoller(BinanceFuturesClient futuresClient,
                                        @Qualifier("futuresKlineClient") BinanceKlineClient futuresKlineClient,
                                        DerivativesHistory history, DataSource dataSource,
                                        MarketProperties market, DerivativesProperties derivatives) {
        return new DerivativesPoller(futuresClient, futuresKlineClient, history,
                new DerivativesSnapshotWriter(dataSource), market.symbol(), market.binanceInterval(),
                market.interval(), derivatives.basisPollSeconds(), derivatives.pollSeconds());
    }

    /** Only the candles closing while the bot runs can have snapshots, so an hour of retention is plenty. */
    @Bean
    @ConditionalOnProperty(name = "orderbook.enabled", havingValue = "true")
    OrderBookHistory orderBookHistory() {
        return new OrderBookHistory(Duration.ofHours(1));
    }

    @Bean(destroyMethod = "")
    @ConditionalOnProperty(name = "orderbook.enabled", havingValue = "true")
    OrderBookPoller orderBookPoller(OrderBookHistory history, DataSource dataSource, OrderBookProperties orderBook,
                                    MarketProperties market, BinanceResilience resilience) {
        return new OrderBookPoller(new BinanceDepthClient(resilience, orderBook.restUrl()), history,
                new OrderBookSnapshotWriter(dataSource), market.symbol(), orderBook.depthLevels(),
                orderBook.pollSeconds());
    }

    @Bean(destroyMethod = "")
    BinanceAdapter marketDataSource(MarketProperties market, BinanceProperties binance, BinanceResilience resilience) {
        return new BinanceAdapter(market.dataWsUrl(), market.symbol(), binance.maxRetries(),
                resilience.settings().backoff(Duration.ofMillis(binance.initialBackoffMs()),
                        Duration.ofMillis(binance.maxBackoffMs())));
    }

    @Bean(destroyMethod = "")
    PriceEventBus priceEventBus(PriceBusProperties bus) {
        return new PriceEventBus(bus.queueCapacity(), bus.blockTimeoutMs());
    }

    @Bean(destroyMethod = "close")
    LivePipeline livePipeline(DataSource dataSource, DatabaseWriter dbWriter, TickRetentionJob tickRetention,
                              TradeJournal tradeJournal, JdbcOrderCommandStore orderCommands,
                              PositionManager positionManager, ConnectivityGuard connectivityGuard,
                              AlertNotifier alertNotifier, DashboardState dashboardState,
                              ObjectProvider<DerivativesHistory> derivativesHistory,
                              ObjectProvider<DerivativesPoller> derivativesPoller,
                              ObjectProvider<OrderBookHistory> orderBookHistory,
                              ObjectProvider<OrderBookPoller> orderBookPoller, BinanceResilience resilience,
                              @Qualifier("spotKlineClient") BinanceKlineClient spotKlineClient, BinanceAdapter source, PriceEventBus priceEventBus,
                              MarketProperties market, FeatureProperties features, TradingProperties trading,
                              BinanceProperties binance, IndicatorPeriods periods, PredictionSettings prediction) {
        return new LivePipeline(new LivePipeline.Components(dataSource, dbWriter, tickRetention, tradeJournal,
                orderCommands, positionManager, connectivityGuard, alertNotifier, dashboardState,
                derivativesHistory.getIfAvailable(), derivativesPoller.getIfAvailable(),
                orderBookHistory.getIfAvailable(), orderBookPoller.getIfAvailable(),
                spotKlineClient, source, priceEventBus, resilience),
                market, features, trading, binance, periods, prediction);
    }
}
