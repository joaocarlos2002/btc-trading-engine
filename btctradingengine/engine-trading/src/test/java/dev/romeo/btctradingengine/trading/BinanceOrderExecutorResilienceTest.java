package dev.romeo.btctradingengine.trading;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #100: the executor on the shared Resilience4j policies - re-signing, 429/418, breaker vs entries and exits. */
class BinanceOrderExecutorResilienceTest {

    private HttpServer server;
    private ConnectivityGuard guard;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (guard != null) {
            guard.shutdown();
        }
    }

    private BinanceOrderExecutor executor(int maxRetries) {
        return new BinanceOrderExecutor("key", "secret", "http://localhost:" + server.getAddress().getPort(),
                maxRetries, 10, 50);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String param(String query, String name) {
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        throw new AssertionError("no " + name + " in " + query);
    }

    @Test
    void everyOrderRetryIsResignedWithTheSameClientOrderId() throws Exception {
        List<String> queries = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/order", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            if (queries.size() == 1) {
                // A 429 is rejected before processing, so a new order may be sent again
                respond(exchange, 429, "{\"code\":-1003}");
            } else {
                respond(exchange, 200, "{\"orderId\":7,\"executedQty\":\"1\",\"cummulativeQuoteQty\":\"100\"}");
            }
        });
        server.start();

        assertTrue(executor(3).executeBuyMarket("BTCUSDT", BigDecimal.ONE, "entry-1").success());

        assertEquals(2, queries.size());
        assertNotEquals(param(queries.get(0), "timestamp"), param(queries.get(1), "timestamp"));
        assertNotEquals(param(queries.get(0), "signature"), param(queries.get(1), "signature"));
        assertEquals("entry-1", param(queries.get(1), "newClientOrderId"));
    }

    @Test
    void orderRateLimitHonoursRetryAfter() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/order", exchange -> {
            if (requests.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                respond(exchange, 429, "{\"code\":-1003}");
            } else {
                respond(exchange, 200, "{\"orderId\":7,\"executedQty\":\"1\",\"cummulativeQuoteQty\":\"100\"}");
            }
        });
        server.start();

        long started = System.nanoTime();
        assertTrue(executor(3).executeSellMarket("BTCUSDT", BigDecimal.ONE, "exit-1").success());

        assertEquals(2, requests.get());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 900);
    }

    @Test
    void ipBanOpensTheOrderBreakerAndBlocksEntriesThroughTheGuard() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/order", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "120");
            respond(exchange, 418, "{\"code\":-1003,\"msg\":\"banned\"}");
        });
        server.start();
        BinanceOrderExecutor executor = executor(3);
        guard = new ConnectivityGuard(Duration.ofMinutes(1), false);
        guard.setExchangeHealth(executor::circuitOpenReason);

        assertFalse(executor.executeBuyMarket("BTCUSDT", BigDecimal.ONE, null).success());
        BinanceOrderExecutor.OrderResult duringBan = executor.executeBuyMarket("BTCUSDT", BigDecimal.ONE, null);

        assertEquals(1, requests.get(), "not retried, and nothing is sent while the ban lasts");
        assertTrue(duringBan.error().contains("ban"), duringBan.error());
        assertFalse(guard.isHealthy());
        assertTrue(guard.getUnhealthyReason().contains("POST /api/v3/order"), guard.getUnhealthyReason());
    }

    @Test
    void openOrderBreakerStillSendsTheOrder() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/order", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{\"orderId\":7,\"executedQty\":\"1\",\"cummulativeQuoteQty\":\"100\"}");
        });
        server.start();
        BinanceOrderExecutor executor = executor(0);
        executor.resilience().circuitBreaker(BinanceOrderExecutor.NEW_ORDER).transitionToOpenState();

        // The executor cannot tell an exit from an entry, so it never refuses: the guard blocks entries upstream
        assertTrue(executor.executeSellMarket("BTCUSDT", BigDecimal.ONE, "stop-1").success());
        assertEquals(1, requests.get());
        assertTrue(executor.circuitOpenReason().isPresent());
    }

    /** No network: the orders are faked, the breaker is the executor's real one. */
    private static final class FakeExchange extends BinanceOrderExecutor {
        int sells;

        FakeExchange() {
            super("test-key", "test-secret", "http://127.0.0.1:9", 0, 1, 1);
        }

        @Override
        public OrderResult executeBuyMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            return new OrderResult(true, "1", quantity, new BigDecimal("100"), null);
        }

        @Override
        public OrderResult executeSellMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            sells++;
            return new OrderResult(true, "2", quantity, new BigDecimal("90"), null);
        }

        @Override
        public SymbolFilters getSymbolFilters(String symbol) {
            return new SymbolFilters(symbol, new BigDecimal("10"), new BigDecimal("0.00001"),
                    new BigDecimal("1000"), new BigDecimal("0.00001"));
        }

        @Override
        public Optional<QueriedOrder> queryOrder(String symbol, String clientOrderId) {
            return Optional.empty();
        }

        @Override
        public BalanceResult getBalance(String asset) {
            return new BalanceResult(true, new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"), null);
        }
    }

    @Test
    void openBreakerBlocksNewEntriesButTheStopLossStillExits() {
        FakeExchange exchange = new FakeExchange();
        guard = new ConnectivityGuard(Duration.ofMinutes(1), false);
        guard.setExchangeHealth(exchange::circuitOpenReason);
        Instant now = Instant.parse("2026-09-16T10:00:00Z");
        PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
        manager.setOrderIoExecutor(Runnable::run);
        manager.setRealTradingMode(exchange, new PortfolioManager(new BigDecimal("1000"), new BigDecimal("50")), "BTCUSDT");
        manager.markReconciliationComplete();
        manager.setConnectivityGuard(guard);
        manager.setClock(() -> now);
        assertTrue(manager.openManualBuy(new BigDecimal("100"), now).opened());

        exchange.resilience().circuitBreaker(BinanceOrderExecutor.NEW_ORDER).transitionToOpenState();

        manager.processPriceEvent(new NormalizedPriceEvent("BTCUSDT", new BigDecimal("90"), now, now));
        assertEquals(1, exchange.sells, "the stop is sent although the order breaker is open");
        assertTrue(manager.getOpenPosition().isEmpty());

        PositionManager.ManualBuyResult entry = manager.openManualBuy(new BigDecimal("100"), now);
        assertFalse(entry.opened());
        assertTrue(entry.message().contains("circuit breaker"), entry.message());
    }
}
