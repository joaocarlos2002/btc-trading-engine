package dev.romeo.btctradingengine.metrics;

import dev.romeo.btctradingengine.http.HttpMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** Issue #104: binance.http.requests{endpoint,status} and binance.weight.used{host}. */
class MicrometerHttpMetricsListenerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void countsRequestsAndKeepsTheLastWeightPerHost() {
        MicrometerHttpMetricsListener listener = new MicrometerHttpMetricsListener(registry);

        listener.onResponse("api.binance.com", "/api/v3/depth", 200, 5);
        listener.onResponse("api.binance.com", "/api/v3/depth", 200, 10);
        listener.onResponse("api.binance.com", "/api/v3/klines", 429, -1);
        listener.onResponse("fapi.binance.com", "/fapi/v1/premiumIndex", HttpMetrics.IO_ERROR, -1);
        listener.onResponse("fapi.binance.com", "/fapi/v1/openInterest", 200, 3);

        assertEquals(2, registry.get("binance.http.requests").tags("endpoint", "/api/v3/depth", "status", "200")
                .counter().count());
        assertEquals(1, registry.get("binance.http.requests").tags("endpoint", "/api/v3/klines", "status", "429")
                .counter().count());
        assertEquals(1, registry.get("binance.http.requests")
                .tags("endpoint", "/fapi/v1/premiumIndex", "status", "IO_ERROR").counter().count());
        assertEquals(10, registry.get("binance.weight.used").tag("host", "api.binance.com").gauge().value());
        assertEquals(3, registry.get("binance.weight.used").tag("host", "fapi.binance.com").gauge().value());
    }

    @Test
    void installsAndUninstallsItselfAsTheGlobalListener() {
        MicrometerHttpMetricsListener listener = new MicrometerHttpMetricsListener(registry).install();
        try {
            assertSame(listener, HttpMetrics.listener());
        } finally {
            listener.close();
        }
        assertNotSame(listener, HttpMetrics.listener());
    }
}
