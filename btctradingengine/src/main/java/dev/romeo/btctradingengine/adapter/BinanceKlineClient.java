package dev.romeo.btctradingengine.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.TradeFlow;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BinanceKlineClient {
    // Historical backtests must reflect real market conditions - binance.rest.url may point
    // at testnet, whose price/volume is synthetic and not representative of actual BTC trading.
    private static final String MAINNET_REST_URL = "https://api.binance.com";

    // Closed candles never change, so re-fetching the exact same (symbol, interval, days)
    // window on every backtest call is pure waste - especially at 1m, where 180 days is
    // ~260k candles across ~260 paginated requests. A short TTL still rolls the window
    // forward as time passes without hammering Binance on repeated threshold experiments.
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private final ObjectMapper mapper = new ObjectMapper();
    // Without timeouts a stalled connection blocks the caller forever
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    /** Live warmup candles: market.data.rest.url, never the execution URL, which may be the testnet (issue #76). */
    private final String liveBaseUrl;
    private final String historyBaseUrl;
    private final String klinesPath;

    public BinanceKlineClient() {
        this(Config.getMarketDataRestUrl(), MAINNET_REST_URL, "/api/v3/klines");
    }

    private BinanceKlineClient(String liveBaseUrl, String historyBaseUrl, String klinesPath) {
        this.liveBaseUrl = liveBaseUrl;
        this.historyBaseUrl = historyBaseUrl;
        this.klinesPath = klinesPath;
    }

    /** USD-M perpetual klines: same row format as spot, always from mainnet (issue #53). */
    public static BinanceKlineClient usdmFutures() {
        String futuresUrl = Config.getBinanceFuturesRestUrl();
        return new BinanceKlineClient(futuresUrl, futuresUrl, "/fapi/v1/klines");
    }

    String liveBaseUrl() {
        return liveBaseUrl;
    }

    public List<CandleEvent> loadClosedCandles(String symbol, String interval, int limit) throws Exception {
        return fetchPage(liveBaseUrl, symbol, interval, Math.min(Math.max(limit, 1), 1000), null);
    }

    /**
     * Fetches all closed candles for the last {@code days} directly from Binance mainnet's
     * public klines endpoint, paginating backwards in pages of 1000 via {@code endTime}.
     * Used for on-demand backtests, independent of whatever binance.rest.url is configured for
     * live trading (testnet or mainnet). Results are cached in memory for a few minutes per
     * (symbol, interval, days) so re-running a backtest with different strategy parameters but
     * the same window doesn't re-fetch the same candles from Binance every time.
     */
    public List<CandleEvent> loadClosedCandlesRange(String symbol, String interval, int days) throws Exception {
        String key = symbol + ":" + interval + ":" + days;
        CacheEntry cached = cache.get(key);
        if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cached.candles();
        }

        // Pages arrive newest first; prepending each one to a single list was O(n^2), so they are
        // collected here and joined oldest first once the loop is done (issue #95).
        List<List<CandleEvent>> pages = new ArrayList<>();
        long endTime = System.currentTimeMillis();
        long targetStart = endTime - Duration.ofDays(days).toMillis();

        while (endTime > targetStart) {
            List<CandleEvent> page = fetchPage(historyBaseUrl, symbol, interval, 1000, endTime);
            if (page.isEmpty()) {
                break;
            }
            pages.add(page);
            long firstOpenTime = page.get(0).openTime().toEpochMilli();
            if (firstOpenTime >= endTime) {
                break;
            }
            endTime = firstOpenTime - 1;
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        List<CandleEvent> all = new ArrayList<>();
        for (int i = pages.size() - 1; i >= 0; i--) {
            all.addAll(pages.get(i));
        }
        all.removeIf(c -> c.openTime().toEpochMilli() < targetStart);
        cache.put(key, new CacheEntry(all, Instant.now()));
        return all;
    }

    private List<CandleEvent> fetchPage(String baseUrl, String symbol, String interval, int limit, Long endTime) throws Exception {
        String endpoint = baseUrl + klinesPath + "?symbol=" + symbol
                + "&interval=" + interval
                + "&limit=" + limit
                + (endTime != null ? "&endTime=" + endTime : "");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Binance klines returned HTTP " + response.statusCode());
        }

        JsonNode rows = mapper.readTree(response.body());
        long now = Instant.now().toEpochMilli();
        List<CandleEvent> candles = new ArrayList<>();
        for (JsonNode row : rows) {
            long openTime = row.get(0).asLong();
            long closeTime = row.get(6).asLong();
            if (closeTime > now) {
                continue;
            }
            BigDecimal volume = new BigDecimal(row.get(5).asText());
            candles.add(new CandleEvent(
                    symbol,
                    Instant.ofEpochMilli(openTime),
                    Instant.ofEpochMilli(closeTime),
                    new BigDecimal(row.get(1).asText()),
                    new BigDecimal(row.get(2).asText()),
                    new BigDecimal(row.get(3).asText()),
                    new BigDecimal(row.get(4).asText()),
                    volume,
                    row.get(8).asInt(),
                    // index 9 = taker buy base asset volume; klines carry no trade sizes
                    TradeFlow.fromKline(volume, new BigDecimal(row.get(9).asText()))
            ));
        }
        return candles;
    }

    private record CacheEntry(List<CandleEvent> candles, Instant fetchedAt) {}
}
