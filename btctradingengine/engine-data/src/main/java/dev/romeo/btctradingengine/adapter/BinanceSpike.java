package dev.romeo.btctradingengine.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

public class BinanceSpike {
    static void main() throws InterruptedException, JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        HttpClient client = HttpClient.newHttpClient();
        WebSocket.Listener listener = new WebSocket.Listener() {

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                try {
                    JsonNode node = mapper.readTree(data.toString());

                    String priceStr       = node.get("p").asText();
                    long tradeTime        = node.get("T").asLong();
                    String symbol         = node.get("s").asText();
                    BigDecimal price      = new BigDecimal(priceStr);
                    Instant eventTs       = Instant.ofEpochMilli(tradeTime);
                    Instant receiptTs     = Instant.now();

                } catch (Exception e) {
                    System.err.println("Error processing message: " + e.getMessage());
                }

                return WebSocket.Listener.super.onText(webSocket, data, last);
            }
        };

        client.newWebSocketBuilder()
                .buildAsync(URI.create("wss://stream.binance.com:9443/ws/btcusdt@aggTrade"), listener);

        Thread.sleep(20000);
    }
}
