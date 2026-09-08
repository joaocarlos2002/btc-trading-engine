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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class BinanceKlineClient {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    public List<CandleEvent> loadClosedCandles(String symbol, String interval, int limit) throws Exception {
        String endpoint = Config.getBinanceRestUrl()
                + "/api/v3/klines?symbol=" + symbol
                + "&interval=" + interval
                + "&limit=" + Math.min(Math.max(limit, 1), 1000);

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

