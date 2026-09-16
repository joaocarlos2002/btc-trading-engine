package dev.romeo.btctradingengine.resilience;

import java.time.Duration;

/**
 * A Binance REST endpoint with its request weight (issue #100). The name keys the circuit breaker and the
 * retry, so it is stable per endpoint; the weight may depend on the call's {@code limit}.
 *
 * @param timeout null for binance.resilience.request.timeout; set for large downloads
 */
public record BinanceEndpoint(String name, BinanceApi api, int weight, Duration timeout) {

    public BinanceEndpoint {
        if (weight < 0) {
            throw new IllegalArgumentException("weight must not be negative");
        }
    }

    public static BinanceEndpoint spot(String method, String path, int weight) {
        return new BinanceEndpoint("spot " + method + " " + path, BinanceApi.SPOT, weight, null);
    }

    public static BinanceEndpoint futures(String method, String path, int weight) {
        return new BinanceEndpoint("futures " + method + " " + path, BinanceApi.FUTURES, weight, null);
    }

    public static BinanceEndpoint archive(String dataset, Duration timeout) {
        return new BinanceEndpoint("archive " + dataset, BinanceApi.DATA_ARCHIVE, 0, timeout);
    }

    /** GET /api/v3/klines: weight 2 for any limit. */
    public static BinanceEndpoint spotKlines() {
        return spot("GET", "/api/v3/klines", 2);
    }

    /** GET /api/v3/depth: 5 up to 100 levels, 25 up to 500, 50 up to 1000, 250 up to 5000. */
    public static BinanceEndpoint spotDepth(int limit) {
        int weight = limit <= 100 ? 5 : limit <= 500 ? 25 : limit <= 1000 ? 50 : 250;
        return spot("GET", "/api/v3/depth", weight);
    }

    /** GET /fapi/v1/klines: 1 below 100, 2 below 500, 5 up to 1000, 10 above. */
    public static BinanceEndpoint futuresKlines(int limit) {
        int weight = limit < 100 ? 1 : limit < 500 ? 2 : limit <= 1000 ? 5 : 10;
        return futures("GET", "/fapi/v1/klines", weight);
    }

    Duration timeoutOr(Duration fallback) {
        return timeout != null ? timeout : fallback;
    }
}
