package dev.romeo.btctradingengine.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.romeo.btctradingengine.config.Config;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class BinanceUserDataStreamClient {
    private static final Logger logger = LoggerFactory.getLogger(BinanceUserDataStreamClient.class);

    private final String apiKey;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final int maxRetries = Config.getBinanceMaxRetries();
    private final long initialBackoffMs = Config.getBinanceInitialBackoffMs();
    private final long maxBackoffMs = Config.getBinanceMaxBackoffMs();
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private WebSocket webSocket;
    private String listenKey;
    private Consumer<ExecutionReport> executionReportListener;
    private Consumer<String> connectionStatusListener;
    private volatile boolean connected = false;
    private volatile boolean running = false;
    private static final long LISTEN_KEY_REFRESH_INTERVAL_MS = 20 * 60 * 1000; // 20 minutes

    public BinanceUserDataStreamClient(String apiKey) {
        this.apiKey = apiKey;
        this.baseUrl = Config.getBinanceRestUrl();
        this.httpClient = HttpClient.newHttpClient();
    }

    public boolean connect() {
        running = true;
        boolean success = attemptConnect();
        if (success) {
            reconnectAttempts.set(0);
        }
        return success;
    }

    private boolean attemptConnect() {
        try {
            // Step 1: Get listenKey
            listenKey = createListenKey();
            if (listenKey == null) {
                logger.error("âœ— Failed to create listen key");
                return false;
            }
            logger.info("âœ“ Listen key obtained: {}", listenKey.substring(0, 20) + "...");

            // Step 2: Connect WebSocket
            String wsUrl = Config.getBinanceWsUrl() + listenKey;
            CompletionStage<WebSocket> webSocketFuture = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), new WebSocketListener());

            webSocket = webSocketFuture.toCompletableFuture().get(10, TimeUnit.SECONDS);
            logger.info("âœ“ WebSocket connected for User Data Stream");

            // Step 3: Start listen key refresh thread
            startListenKeyRefreshThread();

            connected = true;
            notifyConnectionStatus("connected");
            return true;

        } catch (Exception e) {
            logger.error("âœ— Failed to connect User Data Stream: {}", e.getMessage(), e);
            notifyConnectionStatus("disconnected");
            return false;
        }
    }

    public void disconnect() {
        running = false;
        try {
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Closing");
            }
            if (listenKey != null) {
                deleteListenKey(listenKey);
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

        long backoffMs = calculateBackoff(attempt);
        logger.warn("Reconnecting User Data Stream (attempt {}/{}) in {}ms", attempt, maxRetries, backoffMs);

        Thread reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (running && attemptConnect()) {
                reconnectAttempts.set(0);
            }
        }, "UserDataStreamReconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private long calculateBackoff(int attempt) {
        long backoff = initialBackoffMs * (1L << Math.min(attempt - 1, 6));
        return Math.min(backoff, maxBackoffMs);
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

    private String createListenKey() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v3/userDataStream"))
                    .header("X-MBX-APIKEY", apiKey)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode json = mapper.readTree(response.body());
                return json.get("listenKey").asText();
            }
            logger.error("âœ— Failed to create listen key: {}", response.statusCode());
            return null;
        } catch (Exception e) {
            logger.error("âœ— Error creating listen key: {}", e.getMessage(), e);
            return null;
        }
    }

    private void deleteListenKey(String key) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v3/userDataStream?listenKey=" + key))
                    .header("X-MBX-APIKEY", apiKey)
                    .DELETE()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.warn("Error deleting listen key: {}", e.getMessage());
        }
    }

    private void startListenKeyRefreshThread() {
        new Thread(() -> {
            while (connected) {
                try {
                    Thread.sleep(LISTEN_KEY_REFRESH_INTERVAL_MS);
                    refreshListenKey();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "ListenKeyRefresh").start();
    }

    private void refreshListenKey() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v3/userDataStream?listenKey=" + listenKey))
                    .header("X-MBX-APIKEY", apiKey)
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logger.debug("âœ“ Listen key refreshed");
        } catch (Exception e) {
            logger.warn("Error refreshing listen key: {}", e.getMessage());
        }
    }

    private void notifyConnectionStatus(String status) {
        if (connectionStatusListener != null) {
            connectionStatusListener.accept(status);
        }
    }

    private class WebSocketListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            logger.debug("WebSocket opened");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                JsonNode json = mapper.readTree(data.toString());

                // Check for execution report
                if ("executionReport".equals(json.get("e").asText())) {
                    ExecutionReport report = parseExecutionReport(json);
                    if (report != null && executionReportListener != null) {
                        executionReportListener.accept(report);
                    }
                }
            } catch (Exception e) {
                logger.warn("Error parsing message: {}", e.getMessage());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            logger.error("âœ— WebSocket error: {}", error.getMessage());
            connected = false;
            notifyConnectionStatus("error");
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            logger.warn("âœ— WebSocket closed: {} {}", statusCode, reason);
            connected = false;
            notifyConnectionStatus("closed");
            scheduleReconnect();
            return null;
        }
    }

    private ExecutionReport parseExecutionReport(JsonNode json) {
        try {
            return new ExecutionReport(
                    json.get("s").asText(),                    // symbol
                    json.get("c").asLong(),                    // orderId
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

