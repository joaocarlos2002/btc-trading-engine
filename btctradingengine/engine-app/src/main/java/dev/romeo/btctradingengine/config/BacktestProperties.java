package dev.romeo.btctradingengine.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.nio.file.Path;

/**
 * {@code backtest.*}: commission, the aggTrades cache of the size split (issue #51) and the limits of
 * the kline cache (issue #85).
 */
@Validated
@ConfigurationProperties("backtest")
public record BacktestProperties(
        @Name("commission.rate") @NotNull
        @DecimalMin(value = "0", message = "backtest.commission.rate cannot be negative")
        BigDecimal commissionRate,
        /* Blank means a folder under java.io.tmpdir, like the metrics cache. */
        @Name("aggtrades.cache.dir") String aggTradesCacheDir,
        @Name("kline.cache.max.entries") @Min(value = 1, message = "backtest.kline.cache.max.entries must be positive")
        int klineCacheMaxEntries,
        @Name("kline.cache.max.candles") @Min(value = 1, message = "backtest.kline.cache.max.candles must be positive")
        long klineCacheMaxCandles
) {
    public Path aggTradesCachePath() {
        return CacheDirs.resolve(aggTradesCacheDir, "aggtrades");
    }
}
