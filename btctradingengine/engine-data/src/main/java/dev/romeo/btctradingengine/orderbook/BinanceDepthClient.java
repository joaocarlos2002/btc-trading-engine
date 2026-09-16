package dev.romeo.btctradingengine.orderbook;

import dev.romeo.btctradingengine.http.HttpMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Spot order book depth from /api/v3/depth (issue #10). Uses orderbook.rest.url, mainnet by default:
 * the testnet book is synthetic and says nothing about real execution conditions.
 */
public class BinanceDepthClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String baseUrl;

    public BinanceDepthClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public DepthSnapshot fetch(String symbol, int levels) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v3/depth?symbol=" + symbol + "&limit=" + levels))
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = HttpMetrics.send(client, request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Binance depth returned HTTP " + response.statusCode());
        }
        return parse(response.body());
    }

    /** bids and asks are [price, quantity] string pairs; only the quantities are summed. */
    static DepthSnapshot parse(String json) throws IOException {
        JsonNode node = MAPPER.readTree(json);
        return new DepthSnapshot(sumQuantity(node.get("bids")), sumQuantity(node.get("asks")), node.get("bids").size());
    }

    private static BigDecimal sumQuantity(JsonNode levels) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode level : levels) {
            total = total.add(new BigDecimal(level.get(1).asText()));
        }
        return total;
    }

    public record DepthSnapshot(BigDecimal bidVolume, BigDecimal askVolume, int levels) {

        /** bidVolume / (bidVolume + askVolume) in [0, 1]: 0.5 is balanced, 1 means only bids. Null for an empty book. */
        public BigDecimal imbalance() {
            BigDecimal total = bidVolume.add(askVolume);
            if (total.signum() <= 0) {
                return null;
            }
            return bidVolume.divide(total, 8, RoundingMode.HALF_EVEN);
        }
    }
}
