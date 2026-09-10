package dev.romeo.btctradingengine.trading;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
