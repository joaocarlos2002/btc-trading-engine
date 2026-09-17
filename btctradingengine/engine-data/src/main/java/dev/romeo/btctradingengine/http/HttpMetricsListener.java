package dev.romeo.btctradingengine.http;

/**
 * Observer of the Binance REST calls (issue #104), implemented with Micrometer in engine-app so this
 * module stays free of any metrics library. Calls come from whatever thread sent the request and must
 * not block.
 */
public interface HttpMetricsListener {

    HttpMetricsListener NONE = new HttpMetricsListener() {
        @Override
        public void onResponse(String host, String endpoint, int status, long usedWeight1m) {
        }
    };

    /**
     * @param host         request host, e.g. {@code api.binance.com}
     * @param endpoint     URL path without the query string, e.g. {@code /api/v3/klines}
     * @param status       HTTP status, or {@link HttpMetrics#IO_ERROR} when no response arrived
     * @param usedWeight1m the {@code X-MBX-USED-WEIGHT-1M} header, or -1 when absent
     */
    void onResponse(String host, String endpoint, int status, long usedWeight1m);
}
