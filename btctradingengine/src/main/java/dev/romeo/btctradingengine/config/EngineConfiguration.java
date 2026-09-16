package dev.romeo.btctradingengine.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.romeo.btctradingengine.adapter.BinanceAggTradeArchive;
import dev.romeo.btctradingengine.adapter.BinanceKlineClient;
import dev.romeo.btctradingengine.backtest.BacktestParams;
import dev.romeo.btctradingengine.backtest.BinanceBacktestDataLoader;
import dev.romeo.btctradingengine.derivatives.BinanceFuturesClient;
import dev.romeo.btctradingengine.derivatives.BinanceMetricsArchive;
import dev.romeo.btctradingengine.feature.IndicatorPeriods;
import dev.romeo.btctradingengine.indicator.VwapAnchor;
import dev.romeo.btctradingengine.prediction.PredictionSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import javax.sql.DataSource;

/**
 * Beans shared by the live pipeline, the dashboard backtests and the replay tool: the strategy settings
 * built from the typed properties, the Binance market data clients and the connection pool (issue #101).
 */
@Configuration(proxyBeanMethods = false)
public class EngineConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(EngineConfiguration.class);

    @Bean
    IndicatorPeriods indicatorPeriods(IndicatorProperties indicator, FeatureProperties feature) {
        return new IndicatorPeriods(
                indicator.smaPeriod(), indicator.emaPeriod(), indicator.rsiPeriod(), indicator.atrPeriod(),
                indicator.macdFastPeriod(), indicator.macdSlowPeriod(), indicator.macdSignalPeriod(),
                feature.volatilityShortPeriods(), feature.volatilityLongPeriods(), feature.volumeAveragePeriods(),
                indicator.adxPeriod(), indicator.bollingerPeriod(), indicator.bollingerStdDev(),
                indicator.mfiPeriod(), indicator.donchianPeriod(),
                VwapAnchor.fromProperty(indicator.vwapAnchor()), indicator.vwapRollingPeriods(),
                indicator.priceActionLookback(), indicator.priceActionSwingStrength(),
                indicator.cvdPeriod(), indicator.emaSlopePeriods(),
                indicator.vpinBuckets(), indicator.vpinBucketCandles(),
                feature.absorptionDeltaMin(), feature.absorptionVolumeRatioMin(),
                feature.absorptionMaxMoveAtr(), feature.absorptionWindow());
    }

    @Bean
    PredictionSettings predictionSettings(PredictionProperties p) {
        return new PredictionSettings(
                p.rsiOversold(), p.rsiNeutralLow(), p.rsiNeutralHigh(), p.rsiOverbought(),
                p.smaDistanceExtreme(), p.smaDistanceModerate(),
                p.macdStrongHistogramAtrRatio(),
                p.atrVolatilityLow(), p.atrVolatilityNormal(), p.atrVolatilityHigh(),
                p.volatilityRatioHigh(),
                p.adxTrendMin(), p.adxTrendStrong(),
                p.mfiOversold(), p.mfiNeutralLow(), p.mfiNeutralHigh(), p.mfiOverbought(),
                p.bollingerSqueezeThreshold(),
                p.regimeGatingEnabled(),
                p.vpinFilterEnabled(), p.vpinHighThreshold(),
                p.orderBookFilterEnabled(), p.orderBookBuyMin(), p.orderBookSellMax(),
                p.buyThreshold(), p.sellThreshold(), p.confirmationSnapshots());
    }

    /** The configured strategy as backtest defaults; every /api/backtest query parameter overrides one component. */
    @Bean
    BacktestParams liveBacktestParams(IndicatorPeriods periods, PredictionSettings prediction,
                                      TradingProperties trading, BacktestProperties backtest) {
        return BacktestParams.of(periods, prediction, trading.allowShort(), trading.targetPercent(),
                trading.stopLossPercent(), backtest.commissionRate());
    }

    /** Spot klines: live warmup from market.data.rest.url, backtest history from mainnet. */
    @Bean
    BinanceKlineClient spotKlineClient(MarketProperties market, BacktestProperties backtest) {
        return new BinanceKlineClient(market.dataRestUrl(), backtest.klineCacheMaxEntries(),
                backtest.klineCacheMaxCandles());
    }

    @Bean
    BinanceKlineClient futuresKlineClient(BinanceProperties binance, BacktestProperties backtest) {
        return BinanceKlineClient.usdmFutures(binance.futuresRestUrl(), backtest.klineCacheMaxEntries(),
                backtest.klineCacheMaxCandles());
    }

    @Bean
    BinanceFuturesClient binanceFuturesClient(BinanceProperties binance) {
        return new BinanceFuturesClient(binance.futuresRestUrl());
    }

    @Bean
    BinanceBacktestDataLoader backtestDataLoader(@Qualifier("spotKlineClient") BinanceKlineClient spotKlineClient,
                                                 @Qualifier("futuresKlineClient") BinanceKlineClient futuresKlineClient,
                                                 BinanceFuturesClient binanceFuturesClient,
                                                 MarketProperties market, FeatureProperties feature,
                                                 BinanceProperties binance, DerivativesProperties derivatives,
                                                 BacktestProperties backtest) {
        return new BinanceBacktestDataLoader(spotKlineClient, futuresKlineClient, binanceFuturesClient,
                new BinanceMetricsArchive(binance.dataUrl(), derivatives.metricsCachePath()),
                new BinanceAggTradeArchive(binance.dataUrl(), backtest.aggTradesCachePath(), market.interval()),
                new BinanceBacktestDataLoader.Settings(market.symbol(), market.binanceInterval(),
                        feature.largeTradeNotional(), derivatives.enabled(),
                        derivatives.staleAfter(), derivatives.openInterestChangeWindow()));
    }

    /**
     * The PostgreSQL pool. Lazy: only the live pipeline and the replay tool open it, so the dashboard
     * alone (and its tests) never connects.
     */
    @Bean(destroyMethod = "close")
    @Lazy
    HikariDataSource dataSource(DbProperties db) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(db.url());
        config.setUsername(db.user());
        config.setPassword(db.password());
        config.setMaximumPoolSize(db.poolSize());
        config.setIdleTimeout(db.poolIdleTimeoutMs());
        config.setMaxLifetime(db.poolMaxLifetimeMs());
        config.setPoolName("btc-trading-engineBTCPool");

        HikariDataSource dataSource = new HikariDataSource(config);
        logger.info("HikariCP connection pool initialized with {} connections", db.poolSize());
        return dataSource;
    }
}
