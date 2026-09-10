package dev.romeo.btctradingengine.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class BinanceKlineClient {
    // Historical backtests must reflect real market conditions - binance.rest.url may point
    // at testnet, whose price/volume is synthetic and not representative of actual BTC trading.
    private static final String MAINNET_REST_URL = "https://api.binance.com";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    public List<CandleEvent> loadClosedCandles(String symbol, String interval, int limit) throws Exception {
        return fetchPage(Config.getBinanceRestUrl(), symbol, interval, Math.min(Math.max(limit, 1), 1000), null);
    }

    /**
     * Fetches all closed candles for the last {@code days} directly from Binance mainnet's
     * public klines endpoint, paginating backwards in pages of 1000 via {@code endTime}.
     * Used for on-demand backtests, independent of whatever binance.rest.url is configured for
     * live trading (testnet or mainnet).
     */
    public List<CandleEvent> loadClosedCandlesRange(String symbol, String interval, int days) throws Exception {
        List<CandleEvent> all = new ArrayList<>();
        long endTime = System.currentTimeMillis();
        long targetStart = endTime - Duration.ofDays(days).toMillis();

        while (endTime > targetStart) {
            List<CandleEvent> page = fetchPage(MAINNET_REST_URL, symbol, interval, 1000, endTime);
            if (page.isEmpty()) {
                break;
            }
            all.addAll(0, page);
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

        all.removeIf(c -> c.openTime().toEpochMilli() < targetStart);
        return all;
    }

    private List<CandleEvent> fetchPage(String baseUrl, String symbol, String interval, int limit, Long endTime) throws Exception {
        String endpoint = baseUrl + "/api/v3/klines?symbol=" + symbol
                + "&interval=" + interval
                + "&limit=" + limit
                + (endTime != null ? "&endTime=" + endTime : "");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
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
            candles.add(new CandleEvent(
                    symbol,
                    Instant.ofEpochMilli(openTime),
                    Instant.ofEpochMilli(closeTime),
                    new BigDecimal(row.get(1).asText()),
                    new BigDecimal(row.get(2).asText()),
                    new BigDecimal(row.get(3).asText()),
                    new BigDecimal(row.get(4).asText()),
                    new BigDecimal(row.get(5).asText()),
                    row.get(8).asInt()
            ));
        }
        return candles;
    }
}
