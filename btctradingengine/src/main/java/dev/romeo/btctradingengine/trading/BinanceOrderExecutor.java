package dev.romeo.btctradingengine.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.romeo.btctradingengine.config.Config;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

public class BinanceOrderExecutor {
    private static final Logger logger = LoggerFactory.getLogger(BinanceOrderExecutor.class);

    private final String apiKey;
    private final String apiSecret;
    private final String baseUrl = Config.getBinanceRestUrl();
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public BinanceOrderExecutor(String apiKey, String apiSecret) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    public OrderResult executeBuyMarket(String symbol, BigDecimal quantity) {
        return executeMarketOrder(symbol, quantity, "BUY");
    }

    public OrderResult executeSellMarket(String symbol, BigDecimal quantity) {
        return executeMarketOrder(symbol, quantity, "SELL");
    }

    private OrderResult executeMarketOrder(String symbol, BigDecimal quantity, String side) {
        if (symbol == null || symbol.isBlank()) {
            return failedOrder("Symbol cannot be blank");
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return failedOrder("Order quantity must be positive");
        }
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            return failedOrder("Binance API credentials are not configured");
        }

        try {
            Map<String, String> params = new TreeMap<>();
            params.put("symbol", symbol);
            params.put("side", side);
            params.put("type", "MARKET");
            params.put("quantity", formatQuantity(quantity));
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));
            params.put("recvWindow", "5000");

            String queryString = buildQueryString(params);
            String signature = generateSignature(queryString);

            String url = baseUrl + "/api/v3/order?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-MBX-APIKEY", apiKey)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = mapper.readTree(response.body());

                // Null-safe parsing
                String orderId = json.has("orderId") && !json.get("orderId").isNull()
                    ? json.get("orderId").asText()
                    : "UNKNOWN";
                String executedQty = json.has("executedQty") && !json.get("executedQty").isNull()
                    ? json.get("executedQty").asText()
                    : "0";
                String cumulativeQuoteQty = json.has("cumulativeQuoteQty") && !json.get("cumulativeQuoteQty").isNull()
                    ? json.get("cumulativeQuoteQty").asText()
                    : "0";

                BigDecimal actualQty = new BigDecimal(executedQty);
                BigDecimal totalCost = new BigDecimal(cumulativeQuoteQty);
                BigDecimal avgPrice = totalCost.compareTo(BigDecimal.ZERO) > 0 && actualQty.compareTo(BigDecimal.ZERO) > 0
                    ? totalCost.divide(actualQty, 8, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

                logger.info("âœ“ Order executed: {} {} {} @ avg {}", side, actualQty, symbol, avgPrice);
                return new OrderResult(true, orderId, actualQty, avgPrice, null);
            } else {
                String error = response.body();
                logger.error("âœ— Order failed: {} {}", response.statusCode(), error);
                return new OrderResult(false, null, BigDecimal.ZERO, BigDecimal.ZERO, error);
            }

        } catch (Exception e) {
            logger.error("âœ— Order execution error: {}", e.getMessage(), e);
            return new OrderResult(false, null, BigDecimal.ZERO, BigDecimal.ZERO, e.getMessage());
        }
    }

    private OrderResult failedOrder(String error) {
        logger.error("âœ— Order rejected before request: {}", error);
        return new OrderResult(false, null, BigDecimal.ZERO, BigDecimal.ZERO, error);
    }

    public BalanceResult getBalance(String asset) {
        try {
            Map<String, String> params = new TreeMap<>();
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));
            params.put("recvWindow", "5000");

            String queryString = buildQueryString(params);
            String signature = generateSignature(queryString);

            String url = baseUrl + "/api/v3/account?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-MBX-APIKEY", apiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = mapper.readTree(response.body());
                JsonNode balances = json.get("balances");

                for (JsonNode bal : balances) {
                    if (bal.get("asset").asText().equals(asset)) {
                        BigDecimal free = new BigDecimal(bal.get("free").asText());
                        BigDecimal locked = new BigDecimal(bal.get("locked").asText());
                        BigDecimal total = free.add(locked);

                        logger.debug("Balance {}: free={}, locked={}, total={}", asset, free, locked, total);
                        return new BalanceResult(true, free, locked, total, null);
                    }
                }
                return new BalanceResult(false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    "Asset " + asset + " not found");
            } else {
                String error = response.body();
                logger.error("âœ— Balance fetch failed: {} {}", response.statusCode(), error);
                return new BalanceResult(false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, error);
            }

        } catch (Exception e) {
            logger.error("âœ— Balance fetch error: {}", e.getMessage(), e);
            return new BalanceResult(false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, e.getMessage());
        }
    }

    private String generateSignature(String data) throws Exception {
        Mac sha256 = Mac.getInstance("HmacSHA256");
        SecretKeySpec key = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), 0, apiSecret.getBytes(StandardCharsets.UTF_8).length, "HmacSHA256");
        sha256.init(key);
        byte[] hash = sha256.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hash);
    }

    private String buildQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) sb.append("&");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
        return sb.toString();
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private String formatQuantity(BigDecimal qty) {
        return qty.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }

    public record OrderResult(
            boolean success,
            String orderId,
            BigDecimal executedQuantity,
            BigDecimal averagePrice,
            String error
    ) {}

    public record BalanceResult(
            boolean success,
            BigDecimal free,
            BigDecimal locked,
            BigDecimal total,
            String error
    ) {}

    public List<OpenOrder> getOpenOrders(String symbol) {
        List<OpenOrder> orders = new ArrayList<>();
        try {
            Map<String, String> params = new TreeMap<>();
            params.put("symbol", symbol);
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));
            params.put("recvWindow", "5000");

            String queryString = buildQueryString(params);
            String signature = generateSignature(queryString);

            String url = baseUrl + "/api/v3/openOrders?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-MBX-APIKEY", apiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode jsonArray = mapper.readTree(response.body());
                for (JsonNode order : jsonArray) {
                    String orderId = order.get("orderId").asText();
                    String orderSymbol = order.get("symbol").asText();
                    String side = order.get("side").asText();
                    String origQty = order.get("origQty").asText();
                    String executedQty = order.get("executedQty").asText();
                    String price = order.get("price").asText();
                    String status = order.get("status").asText();
                    long time = order.get("time").asLong();

                    orders.add(new OpenOrder(
                            orderId,
                            orderSymbol,
                            side,
                            new BigDecimal(origQty),
                            new BigDecimal(executedQty),
                            new BigDecimal(price),
                            status,
                            time
                    ));
                }
                logger.debug("Found {} open orders for {}", orders.size(), symbol);
            } else {
                logger.error("âœ— Failed to fetch open orders: {} {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            logger.error("âœ— Error fetching open orders: {}", e.getMessage(), e);
        }
        return orders;
    }

    public record OpenOrder(
            String orderId,
            String symbol,
            String side,
            BigDecimal origQuantity,
            BigDecimal executedQuantity,
            BigDecimal price,
            String status,
            long time
    ) {}

    public SymbolFilters getSymbolFilters(String symbol) {
        try {
            String url = baseUrl + "/api/v3/exchangeInfo?symbol=" + symbol;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = mapper.readTree(response.body());
                JsonNode symbolData = json.get("symbols").get(0);
                JsonNode filters = symbolData.get("filters");

                BigDecimal minNotional = BigDecimal.ZERO;
                BigDecimal minQty = BigDecimal.ZERO;
                BigDecimal maxQty = BigDecimal.ZERO;
                BigDecimal stepSize = BigDecimal.ONE;

                for (JsonNode filter : filters) {
                    String filterType = filter.get("filterType").asText();

                    if ("MIN_NOTIONAL".equals(filterType)) {
                        minNotional = new BigDecimal(filter.get("minNotional").asText());
                    } else if ("LOT_SIZE".equals(filterType)) {
                        minQty = new BigDecimal(filter.get("minQty").asText());
                        maxQty = new BigDecimal(filter.get("maxQty").asText());
                        stepSize = new BigDecimal(filter.get("stepSize").asText());
                    }
                }

                SymbolFilters result = new SymbolFilters(symbol, minNotional, minQty, maxQty, stepSize);
                logger.debug("Symbol filters for {}: minNotional={}, minQty={}, maxQty={}, stepSize={}",
                        symbol, minNotional, minQty, maxQty, stepSize);
                return result;
            } else {
                logger.error("âœ— Failed to fetch symbol filters: {} {}", response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            logger.error("âœ— Error fetching symbol filters: {}", e.getMessage(), e);
            return null;
        }
    }

    public record SymbolFilters(
            String symbol,
            BigDecimal minNotional,
            BigDecimal minQty,
            BigDecimal maxQty,
            BigDecimal stepSize
    ) {}
}

