package dev.romeo.btctradingengine.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code market.*}: the traded symbol, the candle interval and where market data comes from.
 *
 * <p>Market data (ticks, warmup candles) is mainnet by default even while execution
 * ({@code binance.*}) points at the testnet (issue #76).
 */
@Validated
@ConfigurationProperties("market")
public record MarketProperties(
        @NotBlank(message = "market.symbol cannot be blank") String symbol,
        @Name("interval.seconds") @Min(value = 1, message = "market.interval.seconds must be positive")
        long intervalSeconds,
        @Name("binance.interval") @NotNull String binanceInterval,
        @Name("history.candles") @Min(value = 1, message = "market.history.candles must be positive")
        int historyCandles,
        @Name("data.ws.url") @NotNull String dataWsUrl,
        @Name("data.rest.url") @NotNull String dataRestUrl
) {
    public Duration interval() {
        return Duration.ofSeconds(intervalSeconds);
    }

    public boolean dataFromTestnet() {
        return dataWsUrl.toLowerCase().contains("testnet") || dataRestUrl.toLowerCase().contains("testnet");
    }
}
