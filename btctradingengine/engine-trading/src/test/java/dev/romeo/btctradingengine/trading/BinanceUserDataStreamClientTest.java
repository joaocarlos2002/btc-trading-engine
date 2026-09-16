package dev.romeo.btctradingengine.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BinanceUserDataStreamClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_KEY = "vmPUZE6mv9SD5VNHk4HlWFsOr6aKE2zvsw0MuIgwCIPy6utIco14y7Ju91duEh8A";
    private static final String API_SECRET = "NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j";
    private static final String EXECUTION_REPORT = "{\"subscriptionId\":0,\"event\":{\"e\":\"executionReport\","
            + "\"s\":\"BTCUSDT\",\"i\":42,\"c\":\"entry-1\",\"C\":\"\",\"o\":\"MARKET\",\"S\":\"BUY\",\"x\":\"TRADE\","
            + "\"X\":\"FILLED\",\"z\":\"0.5\",\"l\":\"0.5\",\"L\":\"60000\",\"n\":\"0\",\"N\":\"BNB\","
            + "\"T\":1700000000000,\"t\":7}}";

    /** Opens fake sockets that answer the subscribe request with the given status. */
    private static class FakeServer implements BinanceUserDataStreamClient.SocketFactory {
        final List<FakeSocket> sockets = new CopyOnWriteArrayList<>();
        volatile int subscribeStatus = 200;

        @Override
        public CompletableFuture<WebSocket> open(WebSocket.Listener listener) {
            FakeSocket socket = new FakeSocket(listener, subscribeStatus);
            sockets.add(socket);
            listener.onOpen(socket);
            return CompletableFuture.completedFuture(socket);
        }

        FakeSocket last() {
            return sockets.get(sockets.size() - 1);
        }
    }

    private static class FakeSocket implements WebSocket {
        final WebSocket.Listener listener;
        final int subscribeStatus;
        final List<String> sent = new CopyOnWriteArrayList<>();

        FakeSocket(WebSocket.Listener listener, int subscribeStatus) {
            this.listener = listener;
            this.subscribeStatus = subscribeStatus;
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sent.add(data.toString());
            try {
                String id = MAPPER.readTree(data.toString()).path("id").asText();
                listener.onText(this, "{\"id\":\"" + id + "\",\"status\":" + subscribeStatus
                        + ",\"result\":{\"subscriptionId\":0}}", true);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return CompletableFuture.completedFuture(this);
        }

        void receive(String message) {
            listener.onText(this, message, true);
        }

        void close() {
            listener.onClose(this, 1006, "gone");
        }

        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) { return CompletableFuture.completedFuture(this); }
        @Override public void request(long n) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return false; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { }
    }

    private static BinanceUserDataStreamClient client(FakeServer server) {
        return new BinanceUserDataStreamClient(API_KEY, API_SECRET, server, () -> 1747385641636L, 20, 1, 5);
    }

    @Test
    public void subscribeRequestIsSignedWithHmacOfApiKeyAndTimestamp() throws Exception {
        FakeServer server = new FakeServer();
        BinanceUserDataStreamClient client = client(server);
        try {
            assertTrue(client.connect());
            assertTrue(client.isConnected());

            JsonNode request = MAPPER.readTree(server.last().sent.get(0));
            assertEquals("userDataStream.subscribe.signature", request.path("method").asText());
            assertEquals(API_KEY, request.path("params").path("apiKey").asText());
            assertEquals(1747385641636L, request.path("params").path("timestamp").asLong());
            // openssl dgst -sha256 -hmac <secret> of "apiKey=<key>&timestamp=1747385641636"
            assertEquals("681110a6057ad58657af22f5640fab9dd17418834cdfccdc81be8b0f7b2cde3e",
                    request.path("params").path("signature").asText());
        } finally {
            client.disconnect();
        }
    }

    @Test
    public void rejectedSubscriptionFailsConnect() {
        FakeServer server = new FakeServer();
        server.subscribeStatus = 401;
        BinanceUserDataStreamClient client = client(server);
        try {
            assertFalse(client.connect());
            assertFalse(client.isConnected());
        } finally {
            client.disconnect();
        }
    }

    @Test
    public void fragmentedMessageIsParsedOnceComplete() {
        FakeServer server = new FakeServer();
        BinanceUserDataStreamClient client = client(server);
        List<BinanceUserDataStreamClient.ExecutionReport> reports = new CopyOnWriteArrayList<>();
        client.setExecutionReportListener(reports::add);
        try {
            assertTrue(client.connect());
            FakeSocket socket = server.last();

            int third = EXECUTION_REPORT.length() / 3;
            socket.listener.onText(socket, EXECUTION_REPORT.substring(0, third), false);
            socket.listener.onText(socket, EXECUTION_REPORT.substring(third, 2 * third), false);
            assertTrue(reports.isEmpty());
            socket.listener.onText(socket, EXECUTION_REPORT.substring(2 * third), true);

            assertEquals(1, reports.size());
            assertEquals(42L, reports.get(0).orderId());
            assertEquals("entry-1", reports.get(0).clientOrderId());
            assertTrue(reports.get(0).isFilled());

            // Buffer was reset: the next whole message parses on its own
            socket.receive(EXECUTION_REPORT);
            assertEquals(2, reports.size());
        } finally {
            client.disconnect();
        }
    }

    @Test
    public void repeatedReconnectsKeepOneSchedulerThreadAndOnePendingTask() throws Exception {
        FakeServer server = new FakeServer();
        BinanceUserDataStreamClient client = client(server);
        try {
            assertTrue(client.connect());
            for (int i = 1; i <= 5; i++) {
                server.last().close();
                int expected = i + 1;
                waitFor(() -> server.sockets.size() == expected && client.isConnected());
                assertTrue(schedulerThreads() <= 1, "scheduler threads: " + schedulerThreads());
            }
            assertEquals(6, server.sockets.size());
            assertFalse(client.hasPendingReconnect());

            // Callbacks from an old socket don't start another connection
            server.sockets.get(0).close();
            Thread.sleep(50);
            assertEquals(6, server.sockets.size());
        } finally {
            client.disconnect();
        }
        waitFor(() -> schedulerThreads() == 0);
    }

    @Test
    public void streamTerminationTriggersReconnect() throws Exception {
        FakeServer server = new FakeServer();
        BinanceUserDataStreamClient client = client(server);
        try {
            assertTrue(client.connect());
            server.last().receive("{\"subscriptionId\":0,\"event\":{\"e\":\"eventStreamTerminated\",\"E\":1}}");
            waitFor(() -> server.sockets.size() == 2 && client.isConnected());
        } finally {
            client.disconnect();
        }
    }

    @Test
    public void noReconnectAfterDisconnect() throws Exception {
        FakeServer server = new FakeServer();
        BinanceUserDataStreamClient client = client(server);
        assertTrue(client.connect());
        client.disconnect();
        server.last().close();
        Thread.sleep(50);
        assertEquals(1, server.sockets.size());
        assertFalse(client.hasPendingReconnect());
    }

    private static long schedulerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && "UserDataStreamScheduler".equals(t.getName()))
                .count();
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(5);
        }
    }
}
