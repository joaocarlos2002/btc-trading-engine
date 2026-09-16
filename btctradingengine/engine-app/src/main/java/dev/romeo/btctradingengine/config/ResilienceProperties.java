package dev.romeo.btctradingengine.config;

import dev.romeo.btctradingengine.resilience.BinanceResilienceSettings;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code binance.resilience.*}: timeout, retry, circuit breaker and request weight shared by every Binance
 * REST client - live pollers, order executor and backtest loader (issue #100). Bound here and handed to the
 * non-Spring modules as {@link BinanceResilienceSettings}.
 */
@Validated
@ConfigurationProperties("binance.resilience")
public record ResilienceProperties(
        @Name("connect.timeout") @NotNull Duration connectTimeout,
        @Name("request.timeout") @NotNull Duration requestTimeout,
        @Name("retry.max.attempts") @Min(value = 1, message = "binance.resilience.retry.max.attempts must be at least 1")
        int retryMaxAttempts,
        @Name("retry.initial.backoff") @NotNull Duration retryInitialBackoff,
        @Name("retry.multiplier") @DecimalMin(value = "1.0", message = "binance.resilience.retry.multiplier must be >= 1")
        double retryMultiplier,
        @Name("retry.jitter") @DecimalMin("0.0") @DecimalMax(value = "0.99", message = "binance.resilience.retry.jitter must be in [0, 1)")
        double retryJitter,
        @Name("retry.max.backoff") @NotNull Duration retryMaxBackoff,
        @Name("retry.max.retry.after") @NotNull Duration retryMaxRetryAfter,
        @Name("ban.fallback") @NotNull Duration banFallback,
        @Name("breaker.failure.rate") @DecimalMin("1") @DecimalMax("100") float breakerFailureRate,
        @Name("breaker.window.size") @Min(1) int breakerWindowSize,
        @Name("breaker.minimum.calls") @Min(1) int breakerMinimumCalls,
        @Name("breaker.open.duration") @NotNull Duration breakerOpenDuration,
        @Name("breaker.half.open.calls") @Min(1) int breakerHalfOpenCalls,
        @Name("weight.spot.per.minute") @Min(value = 1, message = "binance.resilience.weight.spot.per.minute must be positive")
        int weightSpotPerMinute,
        @Name("weight.futures.per.minute") @Min(value = 1, message = "binance.resilience.weight.futures.per.minute must be positive")
        int weightFuturesPerMinute,
        @Name("weight.max.wait") @NotNull Duration weightMaxWait,
        @Name("weight.bulk.reserve.ratio") @DecimalMin("0.0") @DecimalMax("0.99") double weightBulkReserveRatio,
        @Name("weight.used.backoff.ratio") @DecimalMin("0.1") @DecimalMax("1.0") double weightUsedBackoffRatio
) {
    public BinanceResilienceSettings toSettings() {
        return new BinanceResilienceSettings(connectTimeout, requestTimeout, retryMaxAttempts, retryInitialBackoff,
                retryMultiplier, retryJitter, retryMaxBackoff, retryMaxRetryAfter, banFallback,
                breakerFailureRate, breakerWindowSize, breakerMinimumCalls, breakerOpenDuration, breakerHalfOpenCalls,
                weightSpotPerMinute, weightFuturesPerMinute, weightMaxWait, weightBulkReserveRatio,
                weightUsedBackoffRatio);
    }
}
