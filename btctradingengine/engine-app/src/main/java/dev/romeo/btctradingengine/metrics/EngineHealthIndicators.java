package dev.romeo.btctradingengine.metrics;

import dev.romeo.btctradingengine.adapter.FeedStats;
import dev.romeo.btctradingengine.trading.ConnectivityGuard;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;

/**
 * Health of the live pipeline (issue #104), shown under {@code /actuator/health}. Anonymous callers
 * only see the aggregate status; the details below need a login.
 */
public final class EngineHealthIndicators {

    private EngineHealthIndicators() {
    }

    /**
     * Market data stream: DOWN when the stream reports itself disconnected or no tick arrived for longer
     * than {@code maxTickAge} (trading.max.data.staleness.seconds). Before the first tick the age counts
     * from startup, so a slow warmup only turns it DOWN once the same threshold has passed.
     */
    public static HealthIndicator marketData(FeedStats feed, ConnectivityGuard guard, Duration maxTickAge) {
        return () -> {
            long age = feed.millisSinceLastTick();
            boolean connected = guard.isMarketDataConnected();
            boolean fresh = age <= maxTickAge.toMillis();
            Health.Builder health = connected && fresh ? Health.up() : Health.down();
            return health
                    .withDetail("connected", connected)
                    .withDetail("receivedTicks", feed.hasReceivedTicks())
                    .withDetail("lastTickAgeMs", age)
                    .withDetail("maxTickAgeMs", maxTickAge.toMillis())
                    .withDetail("lastTickLagMs", feed.lastLagMillis())
                    .build();
        };
    }

    /** Binance User Data Stream (fills); only registered with trading.real.enabled=true. */
    public static HealthIndicator userDataStream(ConnectivityGuard guard) {
        return () -> (guard.isUserDataStreamConnected() ? Health.up() : Health.down())
                .withDetail("connected", guard.isUserDataStreamConnected())
                .build();
    }

    /**
     * PostgreSQL through the pool: a connection validated within 2 s. Plain JDBC, since the project has
     * no spring-jdbc for Boot's DataSourceHealthIndicator.
     */
    public static HealthIndicator database(DataSource dataSource) {
        return () -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean valid = connection.isValid(2);
                return (valid ? Health.up() : Health.down())
                        .withDetail("database", connection.getMetaData().getDatabaseProductName())
                        .withDetail("validConnection", valid)
                        .build();
            } catch (Exception e) {
                return Health.down(e).build();
            }
        };
    }

    /** The kill switch that blocks new entries: DOWN exactly when it is blocking them. */
    public static HealthIndicator connectivityGuard(ConnectivityGuard guard) {
        return () -> (guard.isHealthy() ? Health.up() : Health.down())
                .withDetail("reason", guard.getUnhealthyReason())
                .build();
    }
}
