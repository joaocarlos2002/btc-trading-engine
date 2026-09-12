package dev.romeo.btctradingengine.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public class BinanceAdapter implements MarketDataSource {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final String wsUrl = Config.getBinanceWsUrl() + Config.getMarketSymbol().toLowerCase() + "@aggTrade";
    private final int maxRetries = Config.getBinanceMaxRetries();
    private final long initialBackoffMs = Config.getBinanceInitialBackoffMs();
    private final long maxBackoffMs = Config.getBinanceMaxBackoffMs();

    private WebSocket webSocket;
    private PriceEventListener priceListener;
    private Consumer<String> statusListener;
    private volatile boolean running = false;

    private final ReentrantLock fragmentLock = new ReentrantLock();
    private final StringBuilder fragmentBuffer = new StringBuilder();

    private final WebSocket.Listener listener = new WebSocket.Listener() {
        @Override
        public void onOpen(WebSocket webSocket) {
            notifyStatus("connected");
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragmentLock.lock();
            try {
                fragmentBuffer.append(data);

                if (!last) {
                    return WebSocket.Listener.super.onText(webSocket, data, last);
                }

                String completeMessage = fragmentBuffer.toString();
                fragmentBuffer.setLength(0);

                JsonNode node = mapper.readTree(completeMessage);

                String priceStr   = node.get("p").asText();
                long tradeTime    = node.get("T").asLong();
                String symbol     = node.get("s").asText();
                BigDecimal price  = new BigDecimal(priceStr);
                BigDecimal quantity = new BigDecimal(node.get("q").asText());
                Instant eventTs   = Instant.ofEpochMilli(tradeTime);
                Instant receiptTs = Instant.now();

                if (priceListener != null) {
                    NormalizedPriceEvent event = new NormalizedPriceEvent(
                        symbol, price, eventTs, receiptTs, quantity);
                    priceListener.onEvent(event);
                }

            } catch (Exception e) {
                System.err.println("Error processing message: " + e.getMessage());
                fragmentBuffer.setLength(0);
            } finally {
                fragmentLock.unlock();
            }

            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("WebSocket error: " + error.getMessage());
            notifyStatus("error");
            if (running) {
                reconnectAsync();
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("WebSocket closed: " + statusCode + " - " + reason);
            notifyStatus("closed");
            if (running) {
                reconnectAsync();
            }
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }
    };

    public void setStatusListener(Consumer<String> statusListener) {
        this.statusListener = statusListener;
    }

    private void notifyStatus(String status) {
        if (statusListener != null) {
            statusListener.accept(status);
        }
    }

    @Override
    public void start(PriceEventListener priceEventListener) {
        this.priceListener = priceEventListener;
        this.running = true;
        connectWithRetry();
    }

    @Override
    public void stop() {
        running = false;
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Stopping adapter").thenRun(() -> {
                System.out.println("WebSocket fechado com sucesso.");
            });
        }
    }

    private void connectWithRetry() {
        int attempt = 0;
        while (running && attempt < maxRetries) {
            try {
                connect();
                return;
            } catch (Exception e) {
                attempt++;
                long backoffMs = calculateBackoff(attempt);
                System.err.println("Connection attempt " + attempt + " failed: " + e.getMessage() +
                                 ". Retrying in " + backoffMs + "ms...");
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (!running) {
            System.out.println("Connection attempts stopped.");
        } else {
            System.err.println("Max retries reached. Giving up.");
            notifyStatus("failed");
        }
    }

    private void connect() {
        this.webSocket = client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), this.listener)
                .join();
    }

    private void reconnectAsync() {
        Thread reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(1000);
                if (running) {
                    connectWithRetry();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private long calculateBackoff(int attempt) {
        long backoff = initialBackoffMs * (1L << Math.min(attempt - 1, 6));
        return Math.min(backoff, maxBackoffMs);
    }
}
