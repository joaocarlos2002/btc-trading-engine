package dev.romeo.btctradingengine.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The single place where Binance REST requests get their timeout, retry, circuit breaker and request
 * weight (issue #100). One instance is shared by every client of the process - live pollers, the order
 * executor and the backtest loader - so they all draw from the same weight budget per host.
 *
 * <p>Per attempt, in order: the request is (re)built by the caller's supplier, so signed requests get a
 * fresh timestamp and signature; an active IP ban (418) fails fast; non-critical calls wait out a 429
 * Retry-After pause, a used-weight pause and the rate limiter; the breaker may refuse market data; the
 * request is sent under the TimeLimiter; the X-MBX-USED-WEIGHT-1M header, 418 and 429 are recorded.
 * The Retry around it decides on another attempt from {@link BinanceCall}.
 *
 * <p>Plain resilience4j core, no Spring: engine-app builds the instance from binance.resilience.*.
 */
public class BinanceResilience {
    private static final Logger logger = LoggerFactory.getLogger(BinanceResilience.class);
    static final String USED_WEIGHT_HEADER = "X-MBX-USED-WEIGHT-1M";
    private static final int HTTP_IP_BANNED = 418;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final long MINUTE_MS = 60_000;

    private final BinanceResilienceSettings settings;
    private final HttpClient client;
    private final LongSupplier clock;
    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final RateLimiterRegistry rateLimiters;
    private final TimeLimiterRegistry timeLimiters;
    private final Map<String, HostState> hosts = new ConcurrentHashMap<>();

    public BinanceResilience(BinanceResilienceSettings settings) {
        this(settings, HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build(),
                System::currentTimeMillis);
    }

    BinanceResilience(BinanceResilienceSettings settings, HttpClient client, LongSupplier clock) {
        this.settings = settings;
        this.client = client;
        this.clock = clock;
        this.circuitBreakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(settings.breakerFailureRate())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.breakerWindowSize())
                .minimumNumberOfCalls(settings.breakerMinimumCalls())
                .waitDurationInOpenState(settings.breakerOpenDuration())
                .permittedNumberOfCallsInHalfOpenState(settings.breakerHalfOpenCalls())
                // Outcomes are recorded by hand, from status codes as well as exceptions
                .recordException(e -> true)
                .build());
        this.retries = RetryRegistry.ofDefaults();
        this.rateLimiters = RateLimiterRegistry.ofDefaults();
        this.timeLimiters = TimeLimiterRegistry.ofDefaults();
    }

    public BinanceResilienceSettings settings() {
        return settings;
    }

    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return circuitBreakers;
    }

    public RetryRegistry retryRegistry() {
        return retries;
    }

    public RateLimiterRegistry rateLimiterRegistry() {
        return rateLimiters;
    }

    public TimeLimiterRegistry timeLimiterRegistry() {
        return timeLimiters;
    }

    public CircuitBreaker circuitBreaker(BinanceEndpoint endpoint) {
        return circuitBreakers.circuitBreaker(endpoint.name());
    }

    /** True when the endpoint's breaker is OPEN or FORCED_OPEN; half-open lets probes through, so it is not "open" here. */
    public boolean isOpen(BinanceEndpoint endpoint) {
        CircuitBreaker.State state = circuitBreaker(endpoint).getState();
        return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
    }

    /** Last X-MBX-USED-WEIGHT-1M seen from a host (e.g. "api.binance.com"); empty before the first response. */
    public OptionalInt lastUsedWeight(String host) {
        HostState state = hosts.get(host);
        return state == null || state.lastUsedWeight < 0 ? OptionalInt.empty() : OptionalInt.of(state.lastUsedWeight);
    }

    /** Last X-MBX-USED-WEIGHT-1M per host, for the metrics. */
    public Map<String, Integer> lastUsedWeights() {
        Map<String, Integer> weights = new ConcurrentHashMap<>();
        hosts.forEach((host, state) -> {
            if (state.lastUsedWeight >= 0) {
                weights.put(host, state.lastUsedWeight);
            }
        });
        return weights;
    }

    /** Request weight left in the current period of a host's limiter. */
    public int availableWeight(BinanceApi api, String host) {
        return rateLimiter(api, host).getMetrics().getAvailablePermissions();
    }

    public HttpResponse<String> send(BinanceEndpoint endpoint, BinanceCall call, Supplier<HttpRequest> request)
            throws IOException, InterruptedException {
        return send(endpoint, call, request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sends with the endpoint's policies. Returns the last response when retries on 429 / 5xx run out, like
     * a plain send would; throws the last I/O error otherwise, or {@link BinanceRequestRejectedException} when
     * the request was refused locally without being sent.
     */
    public <T> HttpResponse<T> send(BinanceEndpoint endpoint, BinanceCall call, Supplier<HttpRequest> request,
                                    HttpResponse.BodyHandler<T> bodyHandler) throws IOException, InterruptedException {
        CheckedSupplier<HttpResponse<T>> attempt = () -> attempt(endpoint, call, request.get(), bodyHandler);
        try {
            return Retry.decorateCheckedSupplier(retry(endpoint, call), attempt).get();
        } catch (IOException | InterruptedException | RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t.getMessage(), t);
        }
    }

    private <T> HttpResponse<T> attempt(BinanceEndpoint endpoint, BinanceCall call, HttpRequest request,
                                        HttpResponse.BodyHandler<T> bodyHandler) throws IOException, InterruptedException {
        String host = request.uri().getHost();
        HostState state = hosts.computeIfAbsent(host, h -> new HostState());
        long now = clock.getAsLong();

        // Every request during a ban extends it, orders included: nothing is sent until it expires
        if (now < state.bannedUntilMs) {
            throw new BinanceRequestRejectedException(BinanceRequestRejectedException.Reason.IP_BANNED,
                    "Binance IP ban active on " + host + " for another " + (state.bannedUntilMs - now) + "ms; "
                            + endpoint.name() + " not sent");
        }
        if (call.priority != BinanceCall.Priority.CRITICAL) {
            waitForPause(endpoint, state, now);
            acquireWeight(endpoint, call, host);
        } else if (endpoint.weight() > 0) {
            // Counted, but an order or a stop never waits for weight
            rateLimiter(endpoint.api(), host).reservePermission(endpoint.weight());
        }

        CircuitBreaker breaker = circuitBreaker(endpoint);
        boolean permitted = breaker.tryAcquirePermission();
        if (!permitted && call.breakerCanRefuse) {
            throw new BinanceRequestRejectedException(BinanceRequestRejectedException.Reason.CIRCUIT_OPEN,
                    "Circuit breaker " + breaker.getState() + " for " + endpoint.name());
        }

        long start = System.nanoTime();
        HttpResponse<T> response;
        try {
            response = timeLimiter(endpoint).executeFutureSupplier(() -> client.sendAsync(request, bodyHandler));
        } catch (TimeoutException e) {
            recordError(breaker, permitted, start, e);
            throw new HttpTimeoutException(endpoint.name() + " timed out after "
                    + endpoint.timeoutOr(settings.requestTimeout()).toMillis() + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            recordError(breaker, permitted, start, cause);
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(cause.getMessage(), cause);
        } catch (IOException e) {
            // TimeLimiter unwraps the future's ExecutionException
            recordError(breaker, permitted, start, e);
            throw e;
        } catch (InterruptedException e) {
            if (permitted) {
                breaker.releasePermission();
            }
            throw e;
        } catch (Exception e) {
            recordError(breaker, permitted, start, e);
            throw new IOException(e.getMessage(), e);
        }

        recordUsedWeight(state, response);
        int status = response.statusCode();
        if (status == HTTP_IP_BANNED) {
            long banMs = retryAfterMs(response).orElse(settings.banFallback().toMillis());
            state.bannedUntilMs = clock.getAsLong() + banMs;
            logger.error("Binance returned 418 (IP banned) for {}; all requests to {} fail fast for {}ms",
                    endpoint.name(), host, banMs);
            recordError(breaker, permitted, start, new IOException("HTTP 418"));
            breaker.transitionToOpenState();
        } else if (status == HTTP_TOO_MANY_REQUESTS) {
            long pauseMs = retryAfterMs(response).orElse(settings.initialBackoff().toMillis());
            state.pausedUntilMs = Math.max(state.pausedUntilMs, clock.getAsLong() + pauseMs);
            logger.warn("Binance rate limit (429) on {}; non-critical requests to {} paused for {}ms",
                    endpoint.name(), host, pauseMs);
            recordError(breaker, permitted, start, new IOException("HTTP 429"));
        } else if (status >= 500) {
            recordError(breaker, permitted, start, new IOException("HTTP " + status));
        } else if (permitted) {
            // 4xx business errors (unknown order, insufficient balance) mean the endpoint is up
            breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
        return response;
    }

    private void recordError(CircuitBreaker breaker, boolean permitted, long start, Throwable error) {
        if (permitted) {
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, error);
        }
    }

    /** A 429 pause or a near-limit used weight: wait for it when short enough, else refuse without sending. */
    private void waitForPause(BinanceEndpoint endpoint, HostState state, long now) throws IOException, InterruptedException {
        if (endpoint.api() == BinanceApi.DATA_ARCHIVE) {
            return;
        }
        long until = state.pausedUntilMs;
        int threshold = (int) Math.floor(settings.weightPerMinute(endpoint.api()) * settings.usedWeightBackoffRatio());
        if (state.lastUsedWeight >= threshold && state.lastUsedWeightAtMs / MINUTE_MS == now / MINUTE_MS) {
            // Binance counts weight per calendar minute: it resets at the next one
            until = Math.max(until, (now / MINUTE_MS + 1) * MINUTE_MS);
        }
        long waitMs = until - now;
        if (waitMs <= 0) {
            return;
        }
        if (waitMs > settings.rateLimiterMaxWait().toMillis()) {
            throw new BinanceRequestRejectedException(BinanceRequestRejectedException.Reason.RATE_LIMITED,
                    endpoint.name() + " paused by Binance rate limiting for another " + waitMs + "ms");
        }
        logger.debug("{} waits {}ms for the Binance rate limit pause", endpoint.name(), waitMs);
        Thread.sleep(waitMs);
    }

    private void acquireWeight(BinanceEndpoint endpoint, BinanceCall call, String host) throws IOException, InterruptedException {
        if (endpoint.weight() == 0 || endpoint.api() == BinanceApi.DATA_ARCHIVE) {
            return;
        }
        RateLimiter limiter = rateLimiter(endpoint.api(), host);
        long deadline = System.nanoTime() + settings.rateLimiterMaxWait().toNanos();
        if (call.priority == BinanceCall.Priority.BULK) {
            int reserve = (int) Math.ceil(settings.weightPerMinute(endpoint.api()) * settings.bulkReserveRatio());
            // History downloads leave the reserve to the live bot; the limiter refreshes every minute
            while (limiter.getMetrics().getAvailablePermissions() - endpoint.weight() < reserve) {
                if (System.nanoTime() > deadline) {
                    throw rateLimited(endpoint, host);
                }
                Thread.sleep(250);
            }
        }
        long remainingNanos = Math.max(0, deadline - System.nanoTime());
        long waitNanos = limiter.reservePermission(endpoint.weight());
        if (waitNanos < 0 || waitNanos > remainingNanos) {
            throw rateLimited(endpoint, host);
        }
        if (waitNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        }
    }

    private BinanceRequestRejectedException rateLimited(BinanceEndpoint endpoint, String host) {
        return new BinanceRequestRejectedException(BinanceRequestRejectedException.Reason.RATE_LIMITED,
                "No request weight available in time for " + endpoint.name() + " on " + host);
    }

    private void recordUsedWeight(HostState state, HttpResponse<?> response) {
        response.headers().firstValue(USED_WEIGHT_HEADER).ifPresent(value -> {
            try {
                state.lastUsedWeight = Integer.parseInt(value.trim());
                state.lastUsedWeightAtMs = clock.getAsLong();
            } catch (NumberFormatException ignored) {
                // A malformed header only loses this reading
            }
        });
    }

    RateLimiter rateLimiter(BinanceApi api, String host) {
        int weight = settings.weightPerMinute(api);
        return rateLimiters.rateLimiter(api + "@" + host, () -> RateLimiterConfig.custom()
                .limitForPeriod(weight)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(settings.rateLimiterMaxWait())
                .build());
    }

    private TimeLimiter timeLimiter(BinanceEndpoint endpoint) {
        Duration timeout = endpoint.timeoutOr(settings.requestTimeout());
        return timeLimiters.timeLimiter(endpoint.name() + "@" + timeout.toMillis(), () -> TimeLimiterConfig.custom()
                .timeoutDuration(timeout)
                .cancelRunningFuture(true)
                .build());
    }

    private Retry retry(BinanceEndpoint endpoint, BinanceCall call) {
        return retries.retry(endpoint.name() + "#" + call.retry, () -> retryConfig(call.retry));
    }

    private RetryConfig retryConfig(BinanceCall.Retry mode) {
        int maxAttempts = mode == BinanceCall.Retry.NONE ? 1 : settings.maxAttempts();
        IntervalBiFunction<Object> interval = (attempt, outcome) -> {
            if (outcome != null && outcome.isRight() && outcome.get() instanceof HttpResponse<?> response) {
                OptionalLong retryAfter = retryAfterMs(response);
                if (retryAfter.isPresent()) {
                    return retryAfter.getAsLong();
                }
            }
            return settings.retryBackoff().apply(attempt);
        };
        return RetryConfig.<Object>custom()
                .maxAttempts(maxAttempts)
                .intervalBiFunction(interval)
                .retryOnResult(result -> result instanceof HttpResponse<?> response && retryableResponse(mode, response))
                .retryOnException(e -> retryableException(mode, e))
                .failAfterMaxAttempts(false)
                .consumeResultBeforeRetryAttempt((attempt, result) -> {
                    // A discarded streamed body must be closed, or its connection leaks
                    if (result instanceof HttpResponse<?> response && response.body() instanceof Closeable body) {
                        try {
                            body.close();
                        } catch (IOException ignored) {
                            // nothing left to release
                        }
                    }
                })
                .build();
    }

    private boolean retryableResponse(BinanceCall.Retry mode, HttpResponse<?> response) {
        int status = response.statusCode();
        if (status == HTTP_TOO_MANY_REQUESTS) {
            // Binance did not process a 429, so even a new order may be sent again - within reason
            return retryAfterMs(response).orElse(0) <= settings.maxRetryAfter().toMillis();
        }
        // A 5xx on a new order means "execution status unknown": only the clientOrderId lookup may resolve it
        return mode == BinanceCall.Retry.IDEMPOTENT && status >= 500;
    }

    static boolean retryableException(BinanceCall.Retry mode, Throwable e) {
        if (e instanceof BinanceRequestRejectedException || e instanceof InterruptedIOException) {
            return false;
        }
        return switch (mode) {
            case IDEMPOTENT -> e instanceof IOException;
            // Proof that the request never left: the connection could not be opened
            case NOT_SENT_ONLY -> e instanceof ConnectException || e instanceof HttpConnectTimeoutException;
            case NONE -> false;
        };
    }

    static OptionalLong retryAfterMs(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return OptionalLong.of(Math.max(0, Long.parseLong(value.trim())) * 1000);
                    } catch (NumberFormatException e) {
                        return OptionalLong.empty();
                    }
                })
                .orElse(OptionalLong.empty());
    }

    private static final class HostState {
        volatile long bannedUntilMs;
        volatile long pausedUntilMs;
        volatile int lastUsedWeight = -1;
        volatile long lastUsedWeightAtMs;
    }
}
