package dev.romeo.btctradingengine.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.romeo.btctradingengine.resilience.BinanceResilienceSettings;
import io.github.resilience4j.core.IntervalFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * User Data Stream over the WebSocket API ({@code userDataStream.subscribe.signature}).
 * The REST listenKey endpoints were retired by Binance on 2026-02-20.
 */
public class BinanceUserDataStreamClient {
    private static final Logger logger = LoggerFactory.getLogger(BinanceUserDataStreamClient.class);

    static final String MAINNET_WS_API_URL = "wss://ws-api.binance.com:443/ws-api/v3";
    static final String TESTNET_WS_API_URL = "wss://ws-api.testnet.binance.vision/ws-api/v3";
    static final String SUBSCRIBE_METHOD = "userDataStream.subscribe.signature";
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** Opens a WebSocket with the given listener; swapped in tests to avoid the network. */
    interface SocketFactory {
        CompletableFuture<WebSocket> open(WebSocket.Listener listener);
    }

    private final String apiKey;
    private final String apiSecret;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SocketFactory socketFactory;
    private final LongSupplier clock;
    private final int maxRetries;
    // Shared exponential-with-jitter policy (issue #100)
    private final IntervalFunction reconnectBackoff;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    // Bumped on every connection attempt so callbacks from older sockets are ignored
    private final AtomicLong generation = new AtomicLong(0);

    private volatile WebSocket webSocket;
    private Consumer<ExecutionReport> executionReportListener;
    private Consumer<String> connectionStatusListener;
    private volatile boolean connected = false;
    private volatile boolean running = false;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pendingReconnect;

    /** @param testnet whether binance.rest.url is the testnet, which selects the matching WebSocket API */
    public BinanceUserDataStreamClient(String apiKey, String apiSecret, boolean testnet,
                                       int maxRetries, long initialBackoffMs, long maxBackoffMs) {
        this(apiKey, apiSecret, testnet, maxRetries, BinanceResilienceSettings.defaults()
                .backoff(Duration.ofMillis(initialBackoffMs), Duration.ofMillis(maxBackoffMs)));
    }

    /** @param reconnectBackoff wait before reconnect attempt n (1-based), e.g. {@link BinanceResilienceSettings#backoff} */
    public BinanceUserDataStreamClient(String apiKey, String apiSecret, boolean testnet,
                                       int maxRetries, IntervalFunction reconnectBackoff) {
        this(apiKey, apiSecret, defaultSocketFactory(defaultWsApiUrl(testnet)), System::currentTimeMillis,
                maxRetries, reconnectBackoff);
    }

    BinanceUserDataStreamClient(String apiKey, String apiSecret, SocketFactory socketFactory, LongSupplier clock,
                                int maxRetries, long initialBackoffMs, long maxBackoffMs) {
        this(apiKey, apiSecret, socketFactory, clock, maxRetries, BinanceResilienceSettings.defaults()
                .backoff(Duration.ofMillis(initialBackoffMs), Duration.ofMillis(maxBackoffMs)));
    }

    BinanceUserDataStreamClient(String apiKey, String apiSecret, SocketFactory socketFactory, LongSupplier clock,
                                int maxRetries, IntervalFunction reconnectBackoff) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.socketFactory = socketFactory;
        this.clock = clock;
        this.maxRetries = maxRetries;
        this.reconnectBackoff = reconnectBackoff;
    }

    static String defaultWsApiUrl(boolean testnet) {
        return testnet ? TESTNET_WS_API_URL : MAINNET_WS_API_URL;
    }

    private static SocketFactory defaultSocketFactory(String wsApiUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(HTTP_CONNECT_TIMEOUT).build();
        return listener -> httpClient.newWebSocketBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .buildAsync(URI.create(wsApiUrl), listener);
    }

    public boolean connect() {
        synchronized (this) {
            running = true;
            if (scheduler == null) {
                scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "UserDataStreamScheduler");
                    t.setDaemon(true);
                    return t;
                });
            }
        }
        boolean success = attemptConnect();
        if (success) {
            reconnectAttempts.set(0);
        }
        return success;
    }

    private boolean attemptConnect() {
        long gen = generation.incrementAndGet();
        WebSocket socket = null;
        try {
            StreamListener listener = new StreamListener(gen);
            socket = socketFactory.open(listener).get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            webSocket = socket;
            logger.info("âœ“ WebSocket API connected for User Data Stream");

            socket.sendText(buildSubscribeRequest(listener.subscribeRequestId), true);
            JsonNode response = listener.subscribeResponse.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (response.path("status").asInt() != 200) {
                logger.error("âœ— User Data Stream subscription rejected: {}", response.path("error"));
                generation.compareAndSet(gen, gen + 1);
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "Subscription rejected");
                notifyConnectionStatus("disconnected");
                return false;
            }
            logger.info("âœ“ Subscribed to User Data Stream (subscriptionId={})",
                    response.path("result").path("subscriptionId").asText());

            connected = true;
            notifyConnectionStatus("connected");
            return true;

        } catch (Exception e) {
            logger.error("âœ— Failed to connect User Data Stream: {}", e.getMessage(), e);
            // Failed attempts must not trigger reconnects from their own socket callbacks
            generation.compareAndSet(gen, gen + 1);
            if (socket != null) {
                socket.abort();
            }
            notifyConnectionStatus("disconnected");
            return false;
        }
    }

    String buildSubscribeRequest(String requestId) throws Exception {
        long timestamp = clock.getAsLong();
        // Signature payload: params sorted by name, without the signature itself
        String payload = "apiKey=" + apiKey + "&timestamp=" + timestamp;
        ObjectNode request = mapper.createObjectNode();
        request.put("id", requestId);
        request.put("method", SUBSCRIBE_METHOD);
        ObjectNode params = request.putObject("params");
        params.put("apiKey", apiKey);
        params.put("timestamp", timestamp);
        params.put("signature", sign(payload));
        return mapper.writeValueAsString(request);
    }

    private String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    public void disconnect() {
        running = false;
        synchronized (this) {
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
                pendingReconnect = null;
            }
        }
        generation.incrementAndGet();
        try {
            WebSocket socket = webSocket;
            if (socket != null) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "Closing");
            }
            connected = false;
            notifyConnectionStatus("disconnected");
            logger.info("âœ“ User Data Stream disconnected");
        } catch (Exception e) {
            logger.warn("Error during disconnect: {}", e.getMessage());
        }
    }

    private void scheduleReconnect() {
        if (!running) {
            return;
        }

        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > maxRetries) {
            logger.error("âœ— Max reconnect attempts ({}) reached for User Data Stream. Giving up; falling back to polling.", maxRetries);
            notifyConnectionStatus("failed");
            return;
        }

        long backoffMs = reconnectBackoff.apply(attempt);
        logger.warn("Reconnecting User Data Stream (attempt {}/{}) in {}ms", attempt, maxRetries, backoffMs);

        synchronized (this) {
            if (scheduler == null) {
                return;
            }
            // Only one pending reconnect at a time
            if (pendingReconnect != null) {
                pendingReconnect.cancel(false);
            }
            pendingReconnect = scheduler.schedule(() -> {
                if (!running) {
                    return;
                }
                if (attemptConnect()) {
                    reconnectAttempts.set(0);
                } else {
                    scheduleReconnect();
                }
            }, backoffMs, TimeUnit.MILLISECONDS);
        }
    }

    public void setExecutionReportListener(Consumer<ExecutionReport> listener) {
        this.executionReportListener = listener;
    }

    public void setConnectionStatusListener(Consumer<String> listener) {
        this.connectionStatusListener = listener;
    }

    public boolean isConnected() {
        return connected && webSocket != null;
    }

    synchronized boolean hasPendingReconnect() {
        return pendingReconnect != null && !pendingReconnect.isDone();
    }

    private void notifyConnectionStatus(String status) {
        if (connectionStatusListener != null) {
            connectionStatusListener.accept(status);
        }
    }

    /** Handles a complete text message (all fragments joined). */
    void handleMessage(String text, StreamListener listener) {
        try {
            JsonNode json = mapper.readTree(text);

            // Response to our subscribe request
            if (json.has("id") && listener.subscribeRequestId.equals(json.path("id").asText())) {
                listener.subscribeResponse.complete(json);
                return;
            }

            JsonNode event = json.path("event");
            String type = event.path("e").asText("");
            switch (type) {
                case "executionReport" -> {
                    ExecutionReport report = parseExecutionReport(event);
                    if (report != null && executionReportListener != null) {
                        executionReportListener.accept(report);
                    }
                }
                case "eventStreamTerminated", "serverShutdown" -> {
                    logger.warn("User Data Stream {} received; reconnecting", type);
                    connectionLost(listener.generation, "closed");
                }
                default -> { }
            }
        } catch (Exception e) {
            logger.warn("Error parsing message: {}", e.getMessage());
        }
    }

    private void connectionLost(long gen, String status) {
        if (gen != generation.get()) {
            return; // stale socket from an earlier connection
        }
        // Invalidate this socket so its later close/error callbacks don't reconnect again
        generation.incrementAndGet();
        connected = false;
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.abort();
        }
        notifyConnectionStatus(status);
        scheduleReconnect();
    }

    class StreamListener implements WebSocket.Listener {
        final long generation;
        final String subscribeRequestId = UUID.randomUUID().toString();
        final CompletableFuture<JsonNode> subscribeResponse = new CompletableFuture<>();
        private final StringBuilder buffer = new StringBuilder();

        StreamListener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            logger.debug("WebSocket opened");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // A message may arrive in several fragments; parse only once complete
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handleMessage(message, this);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            logger.error("âœ— WebSocket error: {}", error.getMessage());
            subscribeResponse.completeExceptionally(error);
            connectionLost(generation, "error");
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            logger.warn("âœ— WebSocket closed: {} {}", statusCode, reason);
            subscribeResponse.completeExceptionally(new IllegalStateException("closed: " + statusCode));
            connectionLost(generation, "closed");
            return null;
        }
    }

    static ExecutionReport parseExecutionReport(JsonNode json) {
        try {
            // "c" is the clientOrderId (text); on a cancel it is the cancel request's id and the
            // original one comes in "C"
            String originalClientOrderId = json.path("C").asText("");
            return new ExecutionReport(
                    json.get("s").asText(),                    // symbol
                    json.get("i").asLong(),                    // orderId
                    originalClientOrderId.isEmpty() ? json.get("c").asText() : originalClientOrderId,
                    json.get("o").asText(),                    // orderType (MARKET, LIMIT, etc)
                    json.get("S").asText(),                    // side (BUY/SELL)
                    json.get("x").asText(),                    // executionType (NEW, FILLED, etc)
                    json.get("X").asText(),                    // orderStatus (NEW, PARTIALLY_FILLED, FILLED, etc)
                    json.get("z").asText(),                    // cumQty (cumulative executed quantity)
                    json.get("l").asText(),                    // lastQty (qty in this execution)
                    json.get("L").asText(),                    // lastPrice (price in this execution)
                    json.get("n").asText(),                    // commission
                    json.get("N").asText(),                    // commissionAsset
                    json.get("T").asLong(),                    // transactionTime
                    json.get("t").asLong()                     // tradeId (-1 if no trade)
            );
        } catch (Exception e) {
            logger.warn("Error parsing execution report: {}", e.getMessage());
            return null;
        }
    }

    public record ExecutionReport(
            String symbol,
            long orderId,
            String clientOrderId,
            String orderType,
            String side,
            String executionType,           // NEW, PARTIALLY_FILLED, FILLED, CANCELED
            String orderStatus,
            String cumulativeQty,
            String lastQty,
            String lastPrice,
            String commission,
            String commissionAsset,
            long transactionTime,
            long tradeId
    ) {
        public boolean isFilled() {
            return "FILLED".equals(orderStatus);
        }

        public boolean isPartiallyFilled() {
            return "PARTIALLY_FILLED".equals(orderStatus);
        }

        public boolean isCanceled() {
            return "CANCELED".equals(orderStatus);
        }

        public boolean isRejected() {
            return "REJECTED".equals(orderStatus);
        }
    }
}
