package dev.romeo.btctradingengine.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stand-in for the Binance WebSocket API behind {@link BinanceUserDataStreamClient} (issue #103).
 * WireMock has no WebSocket support, so the integration tests plug this into the client's socket
 * factory: it accepts the signed {@code userDataStream.subscribe.signature} request and then
 * delivers recorded executionReport frames through the real listener, so the client's own framing,
 * parsing and dispatch all run.
 */
final class RecordedUserDataStream implements BinanceUserDataStreamClient.SocketFactory {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    final List<JsonNode> subscribeRequests = new CopyOnWriteArrayList<>();
    private volatile Socket socket;

    @Override
    public CompletableFuture<WebSocket> open(WebSocket.Listener listener) {
        Socket opened = new Socket(listener);
        socket = opened;
        listener.onOpen(opened);
        return CompletableFuture.completedFuture(opened);
    }

    /** Delivers one text frame, split in two fragments like a large frame can be. */
    void push(String frame) {
        Socket current = socket;
        if (current == null) {
            throw new IllegalStateException("user data stream not connected");
        }
        int half = frame.length() / 2;
        current.listener.onText(current, frame.substring(0, half), false);
        current.listener.onText(current, frame.substring(half), true);
    }

    private final class Socket implements WebSocket {
        final WebSocket.Listener listener;

        Socket(WebSocket.Listener listener) {
            this.listener = listener;
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            try {
                JsonNode request = MAPPER.readTree(data.toString());
                subscribeRequests.add(request);
                listener.onText(
                        this,
                        "{\"id\":\""
                                + request.path("id").asText()
                                + "\",\"status\":200,\"result\":{\"subscriptionId\":0},\"rateLimits\":[]}",
                        true);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {}

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {}
    }
}
