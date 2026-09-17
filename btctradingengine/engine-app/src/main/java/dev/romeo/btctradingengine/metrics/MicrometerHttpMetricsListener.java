package dev.romeo.btctradingengine.metrics;

import dev.romeo.btctradingengine.http.HttpMetrics;
import dev.romeo.btctradingengine.http.HttpMetricsListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code binance.http.requests{endpoint,status}} and {@code binance.weight.used{host}} (issue #104),
 * fed by {@link HttpMetrics}, the hook every Binance REST client calls.
 *
 * <p>Tags stay low-cardinality: the endpoint is the URL path without the query string, and the
 * status is the HTTP code ({@code IO_ERROR} when no response arrived). The weight gauge keeps the
 * last {@code X-MBX-USED-WEIGHT-1M} seen per host, since spot and futures have separate limits.
 */
public class MicrometerHttpMetricsListener implements HttpMetricsListener, AutoCloseable {
    static final String REQUESTS = "binance.http.requests";
    static final String WEIGHT = "binance.weight.used";

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> weights = new ConcurrentHashMap<>();

    public MicrometerHttpMetricsListener(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Makes this the process-wide listener. */
    public MicrometerHttpMetricsListener install() {
        HttpMetrics.setListener(this);
        return this;
    }

    @Override
    public void onResponse(String host, String endpoint, int status, long usedWeight1m) {
        String statusTag = status == HttpMetrics.IO_ERROR ? "IO_ERROR" : Integer.toString(status);
        String path = endpoint == null || endpoint.isEmpty() ? "/" : endpoint;
        counters.computeIfAbsent(path + ' ' + statusTag, key -> Counter.builder(REQUESTS)
                        .description("Binance REST requests by endpoint and HTTP status")
                        .tag("endpoint", path)
                        .tag("status", statusTag)
                        .register(registry))
                .increment();
        if (usedWeight1m >= 0 && host != null) {
            weights.computeIfAbsent(host, h -> {
                AtomicLong holder = new AtomicLong();
                Gauge.builder(WEIGHT, holder, AtomicLong::get)
                        .description("Last X-MBX-USED-WEIGHT-1M reported by Binance (limit is per IP, per minute)")
                        .tag("host", h)
                        .strongReference(true)
                        .register(registry);
                return holder;
            }).set(usedWeight1m);
        }
    }

    /** Uninstalls itself, so a closed context stops receiving calls. */
    @Override
    public void close() {
        if (HttpMetrics.listener() == this) {
            HttpMetrics.setListener(null);
        }
    }
}
