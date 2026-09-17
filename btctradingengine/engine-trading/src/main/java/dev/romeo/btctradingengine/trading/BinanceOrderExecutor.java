package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.resilience.BinanceCall;
import dev.romeo.btctradingengine.resilience.BinanceEndpoint;
import dev.romeo.btctradingengine.resilience.BinanceResilience;
import dev.romeo.btctradingengine.resilience.BinanceResilienceSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public class BinanceOrderExecutor implements ExecutionPort {
    private static final Logger logger = LoggerFactory.getLogger(BinanceOrderExecutor.class);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long CLOCK_SYNC_INTERVAL_MS = Duration.ofMinutes(30).toMillis();

    // Spot request weights (Binance docs); each endpoint also has its own circuit breaker (issue #100)
    static final BinanceEndpoint NEW_ORDER = BinanceEndpoint.spot("POST", "/api/v3/order", 1);
    static final BinanceEndpoint QUERY_ORDER = BinanceEndpoint.spot("GET", "/api/v3/order", 4);
    static final BinanceEndpoint CANCEL_ORDER = BinanceEndpoint.spot("DELETE", "/api/v3/order", 1);
    static final BinanceEndpoint OPEN_ORDERS = BinanceEndpoint.spot("GET", "/api/v3/openOrders", 6);
    static final BinanceEndpoint ACCOUNT = BinanceEndpoint.spot("GET", "/api/v3/account", 20);
    static final BinanceEndpoint EXCHANGE_INFO = BinanceEndpoint.spot("GET", "/api/v3/exchangeInfo", 20);
    static final BinanceEndpoint SERVER_TIME = BinanceEndpoint.spot("GET", "/api/v3/time", 1);
    static final BinanceEndpoint NEW_OCO = BinanceEndpoint.spot("POST", "/api/v3/orderList/oco", 1);
    static final BinanceEndpoint QUERY_ORDER_LIST = BinanceEndpoint.spot("GET", "/api/v3/orderList", 4);
    static final BinanceEndpoint CANCEL_ORDER_LIST = BinanceEndpoint.spot("DELETE", "/api/v3/orderList", 1);
    /** An open breaker on any of these blocks new entries (never exits) through the ConnectivityGuard. */
    private static final List<BinanceEndpoint> ENTRY_CRITICAL = List.of(NEW_ORDER, QUERY_ORDER, ACCOUNT, NEW_OCO);

    private final String apiKey;
    private final String apiSecret;
    private final String baseUrl;
    private final BinanceResilience resilience;
    private final ObjectMapper mapper = new ObjectMapper();
    // serverTime - localTime, applied to signed timestamps so a drifting local clock
    // does not push requests outside recvWindow (-1021)
    private volatile long clockOffsetMs = 0;
    private volatile long lastClockSyncMs = Long.MIN_VALUE;

    /**
     * baseUrl is binance.rest.url, the execution venue. The live bot passes the process-wide
     * {@link BinanceResilience}, so orders count against the same request weight as every other client.
     */
    public BinanceOrderExecutor(String apiKey, String apiSecret, String baseUrl, BinanceResilience resilience) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.baseUrl = baseUrl;
        this.resilience = resilience;
    }

    /** Standalone policies (not shared with other clients) from binance.max.retries and the backoffs; tests and tools. */
    public BinanceOrderExecutor(String apiKey, String apiSecret, String baseUrl,
                          int maxRetries, long initialBackoffMs, long maxBackoffMs) {
        this(apiKey, apiSecret, baseUrl, maxRetries, initialBackoffMs, maxBackoffMs, CONNECT_TIMEOUT, REQUEST_TIMEOUT);
    }

    // Visible for testing: short timeouts keep the "server never answers" test fast.
    BinanceOrderExecutor(String apiKey, String apiSecret, String baseUrl,
                          int maxRetries, long initialBackoffMs, long maxBackoffMs,
                          Duration connectTimeout, Duration requestTimeout) {
        this(apiKey, apiSecret, baseUrl, new BinanceResilience(BinanceResilienceSettings.defaults()
                .withRetry(maxRetries, Duration.ofMillis(initialBackoffMs), Duration.ofMillis(maxBackoffMs))
                .withTimeouts(connectTimeout, requestTimeout)));
    }

    public BinanceResilience resilience() {
        return resilience;
    }

    /**
     * Why new entries must wait: the circuit breaker of an order, order status, account or OCO endpoint is
     * open. Only entries read it - exits, stops and cancels are always sent (issue #100).
     */
    public Optional<String> circuitOpenReason() {
        for (BinanceEndpoint endpoint : ENTRY_CRITICAL) {
            if (resilience.isOpen(endpoint)) {
                return Optional.of("Binance circuit breaker open for " + endpoint.name());
            }
        }
        return Optional.empty();
    }

    /**
     * Sends through the shared policies (issue #100). The request is rebuilt on every attempt, so signed
     * requests carry a fresh timestamp and signature. What may be retried is set by {@link BinanceCall}: a new
     * order only after a 429 or a connect failure, never after a timeout or a dropped connection, where the
     * clientOrderId lookup decides instead. A 418 is returned once and then fails fast until the ban ends.
     */
    private HttpResponse<String> send(BinanceEndpoint endpoint, BinanceCall call, Supplier<HttpRequest> request)
            throws Exception {
        HttpResponse<String> response = resilience.send(endpoint, call, request);
        if (isTimestampRejected(response)) {
            // Re-sync so the next signed request uses a corrected offset
            lastClockSyncMs = Long.MIN_VALUE;
        }
        return response;
    }

    private boolean isTimestampRejected(HttpResponse<String> response) {
        return response.statusCode() == 400 && response.body() != null && response.body().contains("-1021");
    }

    /** Builds a signed request with a fresh timestamp; called once per attempt by send. */
    private HttpRequest signedRequest(String method, String path, Map<String, String> params) {
        Map<String, String> signed = new TreeMap<>(params);
        signed.put("timestamp", String.valueOf(serverTimeMillis()));
        signed.put("recvWindow", "5000");
        String queryString = buildQueryString(signed);
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path + "?" + queryString + "&signature=" + generateSignature(queryString)))
                .header("X-MBX-APIKEY", apiKey)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
    }

    private long serverTimeMillis() {
        long now = System.currentTimeMillis();
        if (lastClockSyncMs == Long.MIN_VALUE || now - lastClockSyncMs > CLOCK_SYNC_INTERVAL_MS) {
            syncClock();
        }
        return System.currentTimeMillis() + clockOffsetMs;
    }

    /** GET /api/v3/time without retries; on failure keeps the previous offset until the next interval. */
    private synchronized void syncClock() {
        lastClockSyncMs = System.currentTimeMillis();
        try {
            long before = System.currentTimeMillis();
            HttpResponse<String> response = resilience.send(SERVER_TIME, BinanceCall.TRADING_ONCE,
                    () -> HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/v3/time")).GET().build());
            long after = System.currentTimeMillis();
            if (response.statusCode() != 200) {
                logger.warn("Binance server time sync failed: HTTP {}", response.statusCode());
                return;
            }
            long serverTime = mapper.readTree(response.body()).path("serverTime").asLong(0);
            if (serverTime > 0) {
                // Midpoint compensates for the round trip
                clockOffsetMs = serverTime - (before + after) / 2;
                logger.debug("Binance clock offset: {}ms", clockOffsetMs);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            logger.warn("Binance server time sync error: {}", e.getMessage());
        }
    }

    public OrderResult executeBuyMarket(String symbol, BigDecimal quantity) {
        return executeBuyMarket(symbol, quantity, null);
    }

    public OrderResult executeBuyMarket(String symbol, BigDecimal quantity, String clientOrderId) {
        return executeMarketOrder(symbol, quantity, "BUY", clientOrderId);
    }

    public OrderResult executeSellMarket(String symbol, BigDecimal quantity) {
        return executeSellMarket(symbol, quantity, null);
    }

    public OrderResult executeSellMarket(String symbol, BigDecimal quantity, String clientOrderId) {
        return executeMarketOrder(symbol, quantity, "SELL", clientOrderId);
    }

    private OrderResult executeMarketOrder(String symbol, BigDecimal quantity, String side, String clientOrderId) {
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
            if (clientOrderId != null && !clientOrderId.isBlank()) {
                params.put("newClientOrderId", clientOrderId);
            }

            HttpResponse<String> response = send(NEW_ORDER, BinanceCall.TRADING_WRITE,
                    () -> signedRequest("POST", "/api/v3/order", params));

            if (response.statusCode() == 200) {
                OrderResult result = parseOrderResult(mapper.readTree(response.body()));
                BigDecimal actualQty = result.executedQuantity();
                BigDecimal avgPrice = result.averagePrice();

                logger.info("âœ“ Order executed: {} {} {} @ avg {}", side, actualQty, symbol, avgPrice);
                return result;
            } else {
                String error = response.body();
                if (response.statusCode() >= 500 && clientOrderId != null && !clientOrderId.isBlank()) {
                    // Binance: a 5xx leaves the execution status UNKNOWN, the order may have filled
                    Optional<OrderResult> recovered = findOrderByClientOrderId(symbol, clientOrderId);
                    if (recovered.isPresent()) {
                        logger.warn("Recovered order after HTTP {}: clientOrderId={}", response.statusCode(), clientOrderId);
                        return recovered.get();
                    }
                }
                logger.error("âœ— Order failed: {} {}", response.statusCode(), error);
                return new OrderResult(false, null, BigDecimal.ZERO, BigDecimal.ZERO, error);
            }

        } catch (Exception e) {
            
            if (clientOrderId != null && !clientOrderId.isBlank()) {
                Optional<OrderResult> recovered = findOrderByClientOrderId(symbol, clientOrderId);
                if (recovered.isPresent()) {
                    logger.warn("Recovered order after ambiguous request: clientOrderId={}", clientOrderId);
                    return recovered.get();
                }
            }
            logger.error("âœ— Order execution error: {}", e.getMessage(), e);
            return new OrderResult(false, null, BigDecimal.ZERO, BigDecimal.ZERO, e.getMessage());
        }
    }

    /**
     * Shared by the POST response and GET /api/v3/order. Binance spells the field
     * "cummulativeQuoteQty" (double m); a misspelling silently yields an average price of 0.
     */
    private OrderResult parseOrderResult(JsonNode json) {
        String orderId = json.hasNonNull("orderId") ? json.get("orderId").asText() : "UNKNOWN";
        BigDecimal qty = new BigDecimal(json.path("executedQty").asText("0"));
        BigDecimal quote = new BigDecimal(json.path("cummulativeQuoteQty").asText("0"));
        BigDecimal avgPrice = qty.signum() > 0 && quote.signum() > 0
                ? quote.divide(qty, 8, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
        return new OrderResult(true, orderId, qty, avgPrice, null);
    }

    private OrderResult failedOrder(String error) {
        logger.error("âœ— Order rejected before request: {}", error);
        return new OrderResult(false, null, BigDecimal.ZERO, BigDecimal.ZERO, error);
    }

    public BalanceResult getBalance(String asset) {
        try {
            HttpResponse<String> response = send(ACCOUNT, BinanceCall.TRADING_READ,
                    () -> signedRequest("GET", "/api/v3/account", Map.of()));

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

    private String generateSignature(String data) {
        try {
            Mac sha256 = Mac.getInstance("HmacSHA256");
            SecretKeySpec key = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256.init(key);
            byte[] hash = sha256.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (java.security.GeneralSecurityException e) {
            // Unchecked so signing can run inside the per-attempt Supplier
            throw new IllegalStateException("Cannot sign Binance request", e);
        }
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
        // Quantities arrive already rounded to the LOT_SIZE step; 4 decimals would truncate BTC (step 0.00001)
        return qty.setScale(8, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString();
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
            HttpResponse<String> response = send(OPEN_ORDERS, BinanceCall.TRADING_READ,
                    () -> signedRequest("GET", "/api/v3/openOrders", Map.of("symbol", symbol)));

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
                        String clientOrderId = order.has("clientOrderId")
                            ? order.get("clientOrderId").asText() : "";

                    orders.add(new OpenOrder(
                            orderId,
                            orderSymbol,
                            side,
                            new BigDecimal(origQty),
                            new BigDecimal(executedQty),
                            new BigDecimal(price),
                                status,
                                time,
                                clientOrderId
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
            long time,
            String clientOrderId
    ) {}

    public boolean cancelOrder(String symbol, String orderId) {
        try {
            HttpResponse<String> response = send(CANCEL_ORDER, BinanceCall.TRADING_READ, () -> signedRequest("DELETE", "/api/v3/order",
                    Map.of("symbol", symbol, "orderId", orderId)));
            boolean success = response.statusCode() == 200;
            if (!success) {
                logger.error("Failed to cancel order {}: {}", orderId, response.body());
            }
            return success;
        } catch (Exception e) {
            logger.error("Error cancelling order {}: {}", orderId, e.getMessage(), e);
            return false;
        }
    }

    public Optional<OrderResult> findOrderByClientOrderId(String symbol, String clientOrderId) {
        return queryOrder(symbol, clientOrderId).map(order -> new OrderResult(
                true, String.valueOf(order.orderId()), order.executedQuantity(), order.averagePrice(), null));
    }

    /**
     * GET /api/v3/order by clientOrderId. Empty when the request fails or Binance does not know the
     * order - which is not the same as "filled", so callers must not assume a fill from it.
     */
    public Optional<QueriedOrder> queryOrder(String symbol, String clientOrderId) {
        return lookupOrder(symbol, clientOrderId).order();
    }

    /**
     * Like {@link #queryOrder} but tells "Binance does not know this order" (-2013) apart from "could
     * not ask": only the first proves an order was never accepted (issue #111 outbox reconciliation).
     */
    public OrderLookup lookupOrder(String symbol, String clientOrderId) {
        try {
            HttpResponse<String> response = send(QUERY_ORDER, BinanceCall.TRADING_READ, () -> signedRequest("GET", "/api/v3/order",
                    Map.of("symbol", symbol, "origClientOrderId", clientOrderId)));
            if (response.statusCode() != 200) {
                String body = response.body();
                boolean notFound = response.statusCode() == 400 && body != null && body.contains("-2013");
                return notFound ? OrderLookup.notFound() : OrderLookup.error(body);
            }
            JsonNode json = mapper.readTree(response.body());
            OrderResult parsed = parseOrderResult(json);
            return OrderLookup.found(new QueriedOrder(json.path("orderId").asLong(), clientOrderId,
                    json.path("status").asText(""), parsed.executedQuantity(), parsed.averagePrice()));
        } catch (Exception e) {
            return OrderLookup.error(e.getMessage());
        }
    }

    public record OrderLookup(OrderListQuery.State state, Optional<QueriedOrder> order, String error) {
        public static OrderLookup found(QueriedOrder order) {
            return new OrderLookup(OrderListQuery.State.FOUND, Optional.of(order), null);
        }

        public static OrderLookup notFound() {
            return new OrderLookup(OrderListQuery.State.NOT_FOUND, Optional.empty(), null);
        }

        public static OrderLookup error(String error) {
            return new OrderLookup(OrderListQuery.State.ERROR, Optional.empty(), error);
        }
    }

    public record QueriedOrder(
            long orderId,
            String clientOrderId,
            String status,                  // NEW, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED, EXPIRED...
            BigDecimal executedQuantity,
            BigDecimal averagePrice
    ) {}

    public SymbolFilters getSymbolFilters(String symbol) {
        try {
            HttpResponse<String> response = send(EXCHANGE_INFO, BinanceCall.TRADING_READ, () -> HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v3/exchangeInfo?symbol=" + symbol))
                    .GET()
                    .build());

            if (response.statusCode() == 200) {
                JsonNode json = mapper.readTree(response.body());
                JsonNode symbolData = json.get("symbols").get(0);
                JsonNode filters = symbolData.get("filters");

                BigDecimal minNotional = BigDecimal.ZERO;
                BigDecimal minQty = BigDecimal.ZERO;
                BigDecimal maxQty = BigDecimal.ZERO;
                BigDecimal stepSize = BigDecimal.ONE;
                BigDecimal tickSize = null;

                for (JsonNode filter : filters) {
                    String filterType = filter.get("filterType").asText();

                    // Spot pairs moved from MIN_NOTIONAL to NOTIONAL; both carry "minNotional"
                    if ("MIN_NOTIONAL".equals(filterType) || "NOTIONAL".equals(filterType)) {
                        minNotional = new BigDecimal(filter.get("minNotional").asText());
                    } else if ("LOT_SIZE".equals(filterType)) {
                        minQty = new BigDecimal(filter.get("minQty").asText());
                        maxQty = new BigDecimal(filter.get("maxQty").asText());
                        stepSize = new BigDecimal(filter.get("stepSize").asText());
                    } else if ("PRICE_FILTER".equals(filterType)) {
                        tickSize = new BigDecimal(filter.get("tickSize").asText());
                    }
                }

                SymbolFilters result = new SymbolFilters(symbol, minNotional, minQty, maxQty, stepSize, tickSize);
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
            BigDecimal stepSize,
            BigDecimal tickSize             // PRICE_FILTER; null when absent
    ) {
        public SymbolFilters(String symbol, BigDecimal minNotional, BigDecimal minQty, BigDecimal maxQty,
                             BigDecimal stepSize) {
            this(symbol, minNotional, minQty, maxQty, stepSize, null);
        }
    }

    /**
     * SELL OCO protecting a BUY (issue #99): LIMIT_MAKER target above the price and STOP_LOSS_LIMIT
     * stop below it, sharing one quantity. Like any new order, retried only when it certainly did not reach
     * Binance (429, connect failure): the caller resolves an ambiguous result by querying the list's clientOrderId.
     */
    public OcoResult placeOcoSell(String symbol, BigDecimal quantity, BigDecimal targetPrice,
                                  BigDecimal stopPrice, BigDecimal stopLimitPrice,
                                  String listClientOrderId, String targetClientOrderId, String stopClientOrderId) {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            return new OcoResult(false, -1, "", "Binance API credentials are not configured");
        }
        Map<String, String> params = new TreeMap<>();
        params.put("symbol", symbol);
        params.put("side", "SELL");
        params.put("quantity", formatQuantity(quantity));
        params.put("listClientOrderId", listClientOrderId);
        params.put("aboveType", "LIMIT_MAKER");
        params.put("abovePrice", formatPrice(targetPrice));
        params.put("aboveClientOrderId", targetClientOrderId);
        params.put("belowType", "STOP_LOSS_LIMIT");
        params.put("belowStopPrice", formatPrice(stopPrice));
        params.put("belowPrice", formatPrice(stopLimitPrice));
        params.put("belowTimeInForce", "GTC");
        params.put("belowClientOrderId", stopClientOrderId);
        try {
            HttpResponse<String> response = send(NEW_OCO, BinanceCall.TRADING_WRITE,
                    () -> signedRequest("POST", "/api/v3/orderList/oco", params));
            if (response.statusCode() != 200) {
                logger.error("OCO order failed: {} {}", response.statusCode(), response.body());
                return new OcoResult(false, -1, "", response.body());
            }
            JsonNode json = mapper.readTree(response.body());
            logger.info("OCO placed: {} qty={} target={} stop={}/{}", listClientOrderId,
                    params.get("quantity"), params.get("abovePrice"), params.get("belowStopPrice"), params.get("belowPrice"));
            return new OcoResult(true, json.path("orderListId").asLong(-1), json.path("listOrderStatus").asText(""), null);
        } catch (Exception e) {
            logger.error("OCO order error for {}: {}", listClientOrderId, e.getMessage(), e);
            return new OcoResult(false, -1, "", e.getMessage());
        }
    }

    /** GET /api/v3/orderList by listClientOrderId. */
    public OrderListQuery queryOrderList(String listClientOrderId) {
        return orderListRequest("GET", Map.of("origClientOrderId", listClientOrderId));
    }

    /** DELETE /api/v3/orderList: cancels both legs. NOT_FOUND when the list is no longer open. */
    public OrderListQuery cancelOrderList(String symbol, String listClientOrderId) {
        return orderListRequest("DELETE", Map.of("symbol", symbol, "listClientOrderId", listClientOrderId));
    }

    private OrderListQuery orderListRequest(String method, Map<String, String> params) {
        try {
            HttpResponse<String> response = send("GET".equals(method) ? QUERY_ORDER_LIST : CANCEL_ORDER_LIST,
                    BinanceCall.TRADING_READ, () -> signedRequest(method, "/api/v3/orderList", params));
            if (response.statusCode() == 200) {
                return new OrderListQuery(OrderListQuery.State.FOUND,
                        mapper.readTree(response.body()).path("listOrderStatus").asText(""), null);
            }
            String body = response.body();
            // -2011 unknown order (cancel), -2013 order does not exist (query)
            boolean notFound = response.statusCode() == 400 && body != null
                    && (body.contains("-2011") || body.contains("-2013"));
            if (!notFound) {
                logger.error("Order list {} failed: {} {}", method, response.statusCode(), body);
            }
            return new OrderListQuery(notFound ? OrderListQuery.State.NOT_FOUND : OrderListQuery.State.ERROR, "", body);
        } catch (Exception e) {
            logger.error("Order list {} error: {}", method, e.getMessage(), e);
            return new OrderListQuery(OrderListQuery.State.ERROR, "", e.getMessage());
        }
    }

    private String formatPrice(BigDecimal price) {
        return price.stripTrailingZeros().toPlainString();
    }

    public record OcoResult(boolean success, long orderListId, String listOrderStatus, String error) {}

    public record OrderListQuery(State state, String listOrderStatus, String error) {
        public enum State { FOUND, NOT_FOUND, ERROR }

        /** EXECUTING while both legs are working; ALL_DONE once a leg filled or the list was cancelled. */
        public boolean isExecuting() {
            return state == State.FOUND && "EXECUTING".equals(listOrderStatus);
        }
    }
}

