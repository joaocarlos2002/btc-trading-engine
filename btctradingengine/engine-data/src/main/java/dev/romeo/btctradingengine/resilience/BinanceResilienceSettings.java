package dev.romeo.btctradingengine.resilience;

import io.github.resilience4j.core.IntervalFunction;

import java.time.Duration;

/**
 * Settings of the shared Binance REST policies (issue #100), bound from {@code binance.resilience.*} in
 * engine-app and passed by constructor here, so this module stays free of Spring.
 *
 * @param connectTimeout            TCP connect timeout of the shared HTTP client
 * @param requestTimeout            TimeLimiter per attempt, from sending the request to the response headers
 * @param maxAttempts               attempts per call including the first one (1 = no retry)
 * @param initialBackoff            first retry wait
 * @param backoffMultiplier         exponential growth of the retry wait
 * @param randomizationFactor       jitter: each wait is drawn from [wait * (1 - f), wait * (1 + f)]
 * @param maxBackoff                cap of a single retry wait
 * @param maxRetryAfter             a 429 whose Retry-After is longer than this is not retried in place
 * @param banFallback               how long calls fail fast after a 418 without a Retry-After header
 * @param breakerFailureRate        percentage of failed calls that opens an endpoint's circuit breaker
 * @param breakerWindowSize         number of calls in the breaker's sliding window
 * @param breakerMinimumCalls       calls needed before the failure rate is evaluated
 * @param breakerOpenDuration       time an open breaker waits before letting probe calls through
 * @param breakerHalfOpenCalls      probe calls allowed while half-open
 * @param spotWeightPerMinute       request weight budget per minute of a spot host (Binance allows 6000)
 * @param futuresWeightPerMinute    request weight budget per minute of a USD-M futures host (Binance allows 2400)
 * @param rateLimiterMaxWait        how long a market data call may wait for weight before it is rejected
 * @param bulkReserveRatio          share of the budget history downloads (backtests) may not touch, kept for the live bot
 * @param usedWeightBackoffRatio    when X-MBX-USED-WEIGHT-1M reaches this share of the budget, non-critical calls wait for the next minute
 */
public record BinanceResilienceSettings(
        Duration connectTimeout,
        Duration requestTimeout,
        int maxAttempts,
        Duration initialBackoff,
        double backoffMultiplier,
        double randomizationFactor,
        Duration maxBackoff,
        Duration maxRetryAfter,
        Duration banFallback,
        float breakerFailureRate,
        int breakerWindowSize,
        int breakerMinimumCalls,
        Duration breakerOpenDuration,
        int breakerHalfOpenCalls,
        int spotWeightPerMinute,
        int futuresWeightPerMinute,
        Duration rateLimiterMaxWait,
        double bulkReserveRatio,
        double usedWeightBackoffRatio
) {
    public BinanceResilienceSettings {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (spotWeightPerMinute < 1 || futuresWeightPerMinute < 1) {
            throw new IllegalArgumentException("weight per minute must be positive");
        }
        if (bulkReserveRatio < 0 || bulkReserveRatio >= 1) {
            throw new IllegalArgumentException("bulkReserveRatio must be in [0, 1)");
        }
        if (randomizationFactor < 0 || randomizationFactor >= 1) {
            throw new IllegalArgumentException("randomizationFactor must be in [0, 1)");
        }
    }

    /** The same values application.properties ships under binance.resilience.*. */
    public static BinanceResilienceSettings defaults() {
        return new BinanceResilienceSettings(
                Duration.ofSeconds(5), Duration.ofSeconds(10),
                4, Duration.ofMillis(500), 2.0, 0.5, Duration.ofSeconds(30),
                Duration.ofSeconds(60), Duration.ofMinutes(2),
                50f, 20, 10, Duration.ofSeconds(30), 3,
                5000, 2000, Duration.ofSeconds(60), 0.2, 0.9);
    }

    /** Legacy knobs (binance.max.retries and the backoffs) on top of the defaults, for standalone clients and tests. */
    public BinanceResilienceSettings withRetry(int maxRetries, Duration initialBackoff, Duration maxBackoff) {
        return new BinanceResilienceSettings(connectTimeout, requestTimeout, Math.max(0, maxRetries) + 1,
                initialBackoff, backoffMultiplier, randomizationFactor, maxBackoff, maxRetryAfter, banFallback,
                breakerFailureRate, breakerWindowSize, breakerMinimumCalls, breakerOpenDuration, breakerHalfOpenCalls,
                spotWeightPerMinute, futuresWeightPerMinute, rateLimiterMaxWait, bulkReserveRatio,
                usedWeightBackoffRatio);
    }

    public BinanceResilienceSettings withTimeouts(Duration connectTimeout, Duration requestTimeout) {
        return new BinanceResilienceSettings(connectTimeout, requestTimeout, maxAttempts,
                initialBackoff, backoffMultiplier, randomizationFactor, maxBackoff, maxRetryAfter, banFallback,
                breakerFailureRate, breakerWindowSize, breakerMinimumCalls, breakerOpenDuration, breakerHalfOpenCalls,
                spotWeightPerMinute, futuresWeightPerMinute, rateLimiterMaxWait, bulkReserveRatio,
                usedWeightBackoffRatio);
    }

    public BinanceResilienceSettings withWeights(int spotWeightPerMinute, int futuresWeightPerMinute,
                                                 Duration rateLimiterMaxWait) {
        return new BinanceResilienceSettings(connectTimeout, requestTimeout, maxAttempts,
                initialBackoff, backoffMultiplier, randomizationFactor, maxBackoff, maxRetryAfter, banFallback,
                breakerFailureRate, breakerWindowSize, breakerMinimumCalls, breakerOpenDuration, breakerHalfOpenCalls,
                spotWeightPerMinute, futuresWeightPerMinute, rateLimiterMaxWait, bulkReserveRatio,
                usedWeightBackoffRatio);
    }

    public BinanceResilienceSettings withBreaker(float failureRate, int windowSize, int minimumCalls,
                                                 Duration openDuration) {
        return new BinanceResilienceSettings(connectTimeout, requestTimeout, maxAttempts,
                initialBackoff, backoffMultiplier, randomizationFactor, maxBackoff, maxRetryAfter, banFallback,
                failureRate, windowSize, minimumCalls, openDuration, breakerHalfOpenCalls,
                spotWeightPerMinute, futuresWeightPerMinute, rateLimiterMaxWait, bulkReserveRatio,
                usedWeightBackoffRatio);
    }

    /** Exponential backoff with jitter of the REST retries. */
    public IntervalFunction retryBackoff() {
        return backoff(initialBackoff, maxBackoff);
    }

    /**
     * The same exponential-with-jitter policy with other bounds: the WebSocket reconnects keep their own
     * binance.initial.backoff.ms / binance.max.backoff.ms but share the multiplier and the jitter.
     */
    public IntervalFunction backoff(Duration initial, Duration max) {
        Duration first = initial.isPositive() ? initial : Duration.ofMillis(1);
        Duration cap = max.compareTo(first) >= 0 ? max : first;
        return IntervalFunction.ofExponentialRandomBackoff(first, backoffMultiplier, randomizationFactor, cap);
    }

    int weightPerMinute(BinanceApi api) {
        return switch (api) {
            case SPOT -> spotWeightPerMinute;
            case FUTURES -> futuresWeightPerMinute;
            case DATA_ARCHIVE -> Integer.MAX_VALUE;
        };
    }
}
