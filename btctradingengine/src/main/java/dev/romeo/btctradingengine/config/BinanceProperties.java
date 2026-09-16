package dev.romeo.btctradingengine.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * {@code binance.*}: the execution venue (orders, user data stream, reconciliation), the futures and
 * data.binance.vision URLs, and the reconnect/retry backoff.
 *
 * <p>The API key and secret come from BTC_ENGINE_BINANCE_API_KEY / _SECRET (environment or .env);
 * {@link #toString()} masks them so they never end up in a log.
 */
@Validated
@ConfigurationProperties("binance")
public record BinanceProperties(
        @Name("ws.url") @NotNull String wsUrl,
        @Name("rest.url") @NotNull String restUrl,
        @Name("api.key") String apiKey,
        @Name("api.secret") String apiSecret,
        @Name("max.retries") int maxRetries,
        @Name("initial.backoff.ms") long initialBackoffMs,
        @Name("max.backoff.ms") long maxBackoffMs,
        @Name("futures.rest.url") @NotNull String futuresRestUrl,
        @Name("data.url") @NotNull String dataUrl
) {
    public BinanceProperties {
        apiKey = apiKey == null ? "" : apiKey;
        apiSecret = apiSecret == null ? "" : apiSecret;
    }

    public boolean testnetEndpoint() {
        return restUrl.toLowerCase().contains("testnet");
    }

    @Override
    public String toString() {
        return "BinanceProperties[wsUrl=" + wsUrl + ", restUrl=" + restUrl
                + ", apiKey=" + mask(apiKey) + ", apiSecret=" + mask(apiSecret)
                + ", maxRetries=" + maxRetries + ", initialBackoffMs=" + initialBackoffMs
                + ", maxBackoffMs=" + maxBackoffMs + ", futuresRestUrl=" + futuresRestUrl + ", dataUrl=" + dataUrl + "]";
    }

    static String mask(String secret) {
        return secret == null || secret.isBlank() ? "" : "***";
    }
}
