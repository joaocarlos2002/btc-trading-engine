package dev.romeo.btctradingengine.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

/**
 * The single hook the Binance REST clients call after each request (issue #104), so request counts
 * and the used weight can be exported without threading a listener through every constructor.
 *
 * <p>Process wide on purpose: the clients are created in several places (beans, LivePipeline, the
 * backtest loader) and all talk to the same rate limit. The default listener does nothing; engine-app
 * installs the Micrometer one when the pipeline starts.
 *
 * <p>Usage at a send site:
 * <pre>{@code
 * HttpResponse<String> response = HttpMetrics.send(client, request, BodyHandlers.ofString());
 * }</pre>
 */
public final class HttpMetrics {
    /** Status reported when the request failed without a response (timeout, connection error). */
    public static final int IO_ERROR = -1;
    static final String USED_WEIGHT_HEADER = "X-MBX-USED-WEIGHT-1M";

    private static volatile HttpMetricsListener listener = HttpMetricsListener.NONE;

    private HttpMetrics() {
    }

    public static void setListener(HttpMetricsListener newListener) {
        listener = Objects.requireNonNullElse(newListener, HttpMetricsListener.NONE);
    }

    public static HttpMetricsListener listener() {
        return listener;
    }

    /** {@code client.send} that reports the outcome, including an I/O failure, before returning or rethrowing. */
    public static <T> HttpResponse<T> send(HttpClient client, HttpRequest request,
                                           HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        try {
            HttpResponse<T> response = client.send(request, handler);
            record(request, response);
            return response;
        } catch (IOException e) {
            recordFailure(request);
            throw e;
        }
    }

    public static void record(HttpRequest request, HttpResponse<?> response) {
        HttpMetricsListener current = listener;
        if (current == HttpMetricsListener.NONE) {
            return;
        }
        long weight = response.headers().firstValue(USED_WEIGHT_HEADER).map(HttpMetrics::parseWeight).orElse(-1L);
        notify(current, request.uri(), response.statusCode(), weight);
    }

    public static void recordFailure(HttpRequest request) {
        HttpMetricsListener current = listener;
        if (current != HttpMetricsListener.NONE) {
            notify(current, request.uri(), IO_ERROR, -1);
        }
    }

    private static void notify(HttpMetricsListener current, URI uri, int status, long weight) {
        try {
            current.onResponse(uri.getHost(), uri.getPath(), status, weight);
        } catch (RuntimeException ignored) {
            // Metrics must never break a request
        }
    }

    static long parseWeight(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
