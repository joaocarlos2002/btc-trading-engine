package dev.romeo.btctradingengine.trading;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BinanceOrderExecutorRetryTest {

    private HttpServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private BinanceOrderExecutor executorFor(int maxRetries) {
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        return new BinanceOrderExecutor("key", "secret", baseUrl, maxRetries, 10, 50);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    public void retriesOnRateLimitThenSucceeds() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/account", exchange -> {
            if (requestCount.incrementAndGet() < 3) {
                respond(exchange, 429, "{\"msg\":\"rate limited\"}");
            } else {
                respond(exchange, 200, "{\"balances\":[{\"asset\":\"USDT\",\"free\":\"100.0\",\"locked\":\"0.0\"}]}");
            }
        });
        server.start();

        BinanceOrderExecutor.BalanceResult result = executorFor(5).getBalance("USDT");

        assertTrue(result.success());
        assertEquals(0, BigDecimal.valueOf(100.0).compareTo(result.total()));
        assertEquals(3, requestCount.get());
    }

    @Test
    public void givesUpAfterMaxRetriesOnPersistentRateLimit() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/account", exchange -> {
            requestCount.incrementAndGet();
            respond(exchange, 429, "{\"msg\":\"rate limited\"}");
        });
        server.start();

        BinanceOrderExecutor.BalanceResult result = executorFor(2).getBalance("USDT");

        assertFalse(result.success());
        assertEquals(3, requestCount.get()); // initial attempt + 2 retries
    }

    @Test
    public void retriesOnConnectionResetForIdempotentCall() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/account", exchange -> {
            if (requestCount.incrementAndGet() < 2) {
                exchange.close(); // simulate a dropped connection / timeout
            } else {
                respond(exchange, 200, "{\"balances\":[{\"asset\":\"USDT\",\"free\":\"50.0\",\"locked\":\"0.0\"}]}");
            }
        });
        server.start();

        BinanceOrderExecutor.BalanceResult result = executorFor(3).getBalance("USDT");

        assertTrue(result.success());
        assertEquals(2, requestCount.get());
    }

    @Test
    public void doesNotRetryOrderPlacementOnConnectionReset() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/order", exchange -> {
            requestCount.incrementAndGet();
            exchange.close(); // simulate a dropped connection, no response
        });
        server.start();

        BinanceOrderExecutor.OrderResult result = executorFor(3)
                .executeBuyMarket("BTCUSDT", BigDecimal.ONE, null);

        assertFalse(result.success());
        assertEquals(1, requestCount.get());
    }

    private static long timestampOf(String query) {
        for (String pair : query.split("&")) {
            if (pair.startsWith("timestamp=")) {
                return Long.parseLong(pair.substring("timestamp=".length()));
            }
        }
        throw new AssertionError("no timestamp in " + query);
    }

    @Test
    public void failsWithinTimeoutWhenServerNeverResponds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/v3/order", exchange -> {
            try {
                release.await(); // never answers while the client waits
            } catch (InterruptedException ignored) {
            }
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        BinanceOrderExecutor executor = new BinanceOrderExecutor("key", "secret", baseUrl, 3, 10, 50,
                Duration.ofMillis(500), Duration.ofMillis(500));

        long start = System.nanoTime();
        BinanceOrderExecutor.OrderResult result = executor.executeBuyMarket("BTCUSDT", BigDecimal.ONE, null);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        release.countDown();

        assertFalse(result.success());
        assertTrue(elapsedMs < 10_000, "took " + elapsedMs + "ms");
        assertTrue(BinanceOrderExecutor.REQUEST_TIMEOUT.compareTo(Duration.ofSeconds(10)) <= 0);
        assertTrue(BinanceOrderExecutor.CONNECT_TIMEOUT.compareTo(Duration.ofSeconds(5)) <= 0);
    }

    @Test
    public void resignsEachRetryWithFreshTimestamp() throws Exception {
        List<Long> timestamps = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/account", exchange -> {
            timestamps.add(timestampOf(exchange.getRequestURI().getRawQuery()));
            if (timestamps.size() < 3) {
                respond(exchange, 429, "{\"msg\":\"rate limited\"}");
            } else {
                respond(exchange, 200, "{\"balances\":[{\"asset\":\"USDT\",\"free\":\"1.0\",\"locked\":\"0.0\"}]}");
            }
        });
        server.start();

        assertTrue(executorFor(5).getBalance("USDT").success());

        assertEquals(3, timestamps.size());
        assertNotEquals(timestamps.get(0), timestamps.get(1));
        assertNotEquals(timestamps.get(1), timestamps.get(2));
    }

    @Test
    public void doesNotRetryWhenIpBanned() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/account", exchange -> {
            requestCount.incrementAndGet();
            respond(exchange, 418, "{\"code\":-1003,\"msg\":\"banned\"}");
        });
        server.start();

        assertFalse(executorFor(5).getBalance("USDT").success());
        assertEquals(1, requestCount.get());
    }

    @Test
    public void signsWithServerClockOffset() throws Exception {
        long skewMs = Duration.ofHours(1).toMillis();
        List<Long> timestamps = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/time", exchange ->
                respond(exchange, 200, "{\"serverTime\":" + (System.currentTimeMillis() + skewMs) + "}"));
        server.createContext("/api/v3/account", exchange -> {
            timestamps.add(timestampOf(exchange.getRequestURI().getRawQuery()));
            respond(exchange, 200, "{\"balances\":[]}");
        });
        server.start();

        executorFor(0).getBalance("USDT");

        assertEquals(1, timestamps.size());
        long drift = timestamps.get(0) - System.currentTimeMillis();
        assertTrue(Math.abs(drift - skewMs) < 5_000, "offset not applied, drift=" + drift);
    }
}
