package dev.romeo.btctradingengine.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code db.*}: the PostgreSQL connection pool and the ticks retention (issue #85). The password
 * comes from BTC_ENGINE_DB_PASSWORD (environment or .env), the variable docker-compose also uses;
 * {@link #toString()} masks it.
 */
@Validated
@ConfigurationProperties("db")
public record DbProperties(
        @NotNull String url,
        @NotNull String user,
        String password,
        @Name("pool.size") int poolSize,
        @Name("pool.idle.timeout.ms") long poolIdleTimeoutMs,
        @Name("pool.max.lifetime.ms") long poolMaxLifetimeMs,
        /* 0 keeps ticks forever. */
        @Name("ticks.retention.days") int ticksRetentionDays,
        @Name("ticks.retention.batch.size") int ticksRetentionBatchSize,
        @Name("ticks.retention.interval.minutes") long ticksRetentionIntervalMinutes
) {
    public DbProperties {
        password = password == null ? "" : password;
    }

    /** At least a minute, as the retention job always ran. */
    public Duration ticksRetentionInterval() {
        return Duration.ofMinutes(Math.max(1, ticksRetentionIntervalMinutes));
    }

    @Override
    public String toString() {
        return "DbProperties[url=" + url + ", user=" + user + ", password=" + BinanceProperties.mask(password)
                + ", poolSize=" + poolSize + ", poolIdleTimeoutMs=" + poolIdleTimeoutMs
                + ", poolMaxLifetimeMs=" + poolMaxLifetimeMs + ", ticksRetentionDays=" + ticksRetentionDays
                + ", ticksRetentionBatchSize=" + ticksRetentionBatchSize
                + ", ticksRetentionIntervalMinutes=" + ticksRetentionIntervalMinutes + "]";
    }
}
