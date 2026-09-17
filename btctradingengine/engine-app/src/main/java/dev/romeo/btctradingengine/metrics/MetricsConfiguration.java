package dev.romeo.btctradingengine.metrics;

import dev.romeo.btctradingengine.adapter.FeedStats;
import dev.romeo.btctradingengine.config.TradingProperties;
import dev.romeo.btctradingengine.persistence.DatabaseWriter;
import dev.romeo.btctradingengine.persistence.TickRetentionJob;
import dev.romeo.btctradingengine.trading.ConnectivityGuard;
import dev.romeo.btctradingengine.trading.PositionManager;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.boot.actuate.metrics.jdbc.DataSourcePoolMetrics;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.metadata.HikariDataSourcePoolMetadata;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Micrometer meters and health indicators (issue #104).
 *
 * <p>The Binance HTTP listener is always installed (the dashboard backtests call Binance too). The
 * pipeline meters and health indicators only exist with {@code engine.pipeline.enabled=true}, since
 * the beans they read only exist then. The lower modules know nothing about Micrometer: they expose
 * plain counters and getters, read here on scrape.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfiguration {

    @Bean(destroyMethod = "close")
    MicrometerHttpMetricsListener binanceHttpMetrics(MeterRegistry registry) {
        return new MicrometerHttpMetricsListener(registry).install();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "engine.pipeline.enabled", havingValue = "true", matchIfMissing = true)
    static class Pipeline {

        @Bean
        PipelineMetrics pipelineMetrics(MeterRegistry registry) {
            return new PipelineMetrics(registry);
        }

        @Bean
        EngineMetrics engineMetrics(FeedStats feedStats, DatabaseWriter databaseWriter,
                                    TickRetentionJob tickRetentionJob, PositionManager positionManager,
                                    TradingProperties trading) {
            return new EngineMetrics(feedStats, databaseWriter, tickRetentionJob, positionManager,
                    trading.maxDrawdownPercent());
        }

        @Bean
        HealthIndicator marketDataHealthIndicator(FeedStats feedStats, ConnectivityGuard connectivityGuard,
                                                  TradingProperties trading) {
            return EngineHealthIndicators.marketData(feedStats, connectivityGuard, trading.maxDataStaleness());
        }

        @Bean
        HealthIndicator connectivityGuardHealthIndicator(ConnectivityGuard connectivityGuard) {
            return EngineHealthIndicators.connectivityGuard(connectivityGuard);
        }

        @Bean
        @ConditionalOnProperty(name = "trading.real.enabled", havingValue = "true")
        HealthIndicator userDataStreamHealthIndicator(ConnectivityGuard connectivityGuard) {
            return EngineHealthIndicators.userDataStream(connectivityGuard);
        }

        /**
         * {@code jdbc.connections.active/idle/max/min}, replacing Boot's pool metrics auto-configuration
         * (excluded in application.properties because it opens the lazy pool without the pipeline).
         */
        @Bean
        DataSourcePoolMetrics dataSourcePoolMetrics(HikariDataSource dataSource) {
            return new DataSourcePoolMetrics(dataSource,
                    ds -> new HikariDataSourcePoolMetadata((HikariDataSource) ds), "dataSource", Tags.empty());
        }

        /**
         * Replaces Boot's "db" indicator (management.health.db.enabled=false): the pool is a lazy bean,
         * and the auto-configured one would open it even when only the dashboard runs.
         */
        @Bean
        HealthIndicator databaseHealthIndicator(DataSource dataSource) {
            return EngineHealthIndicators.database(dataSource);
        }
    }
}
