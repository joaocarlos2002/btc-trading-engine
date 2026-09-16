package dev.romeo.btctradingengine.resilience;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #100: the shared Binance policies - retry, Retry-After, 418 ban, breaker, shared weight, timeout. */
class BinanceResilienceTest {

    private static final BinanceEndpoint TICKER = BinanceEndpoint.spot("GET", "/api/v3/ticker", 2);
    private static final BinanceEndpoint TIME = BinanceEndpoint.spot("GET", "/api/v3/time", 1);

    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        if (server != null) {
            server.stop(0);
        }
    }

    private static BinanceResilienceSettings fast() {
        return BinanceResilienceSettings.defaults()
                .withRetry(2, Duration.ofMillis(10), Duration.ofMillis(50))
                .withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    }

    private Supplier get(String path) {
        return new Supplier("http://localhost:" + server.getAddress().getPort() + path);
    }

    private record Supplier(String url) implements java.util.function.Supplier<HttpRequest> {
        @Override
        public HttpRequest get() {
            return HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void retriesMarketDataOnServerErrorsThenSucceeds() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start();
        server.createContext("/api/v3/ticker", exchange ->
                respond(exchange, requests.incrementAndGet() < 3 ? 503 : 200, "{}"));
        server.start();

        HttpResponse<String> response = new BinanceResilience(fast()).send(TICKER, BinanceCall.MARKET_DATA, get("/api/v3/ticker"));

        assertEquals(200, response.statusCode());
        assertEquals(3, requests.get());
    }

    @Test
    void newOrdersAreNotRetriedOnServerErrors() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start();
        server.createContext("/api/v3/order", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 503, "{\"code\":-1007}");
        });
        server.start();

        HttpResponse<String> response = new BinanceResilience(fast())
                .send(BinanceEndpoint.spot("POST", "/api/v3/order", 1), BinanceCall.TRADING_WRITE, get("/api/v3/order"));

        assertEquals(503, response.statusCode());
        assertEquals(1, requests.get());
    }

    @Test
    void honoursRetryAfterOnRateLimit() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start();
        server.createContext("/api/v3/ticker", exchange -> {
            if (requests.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                respond(exchange, 429, "{\"code\":-1003}");
            } else {
                respond(exchange, 200, "{}");
            }
        });
        server.start();
        BinanceResilience resilience = new BinanceResilience(fast());

        long started = System.nanoTime();
        HttpResponse<String> response = resilience.send(TICKER, BinanceCall.MARKET_DATA, get("/api/v3/ticker"));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertEquals(200, response.statusCode());
        assertEquals(2, requests.get());
        assertTrue(elapsedMs >= 900, "retried after " + elapsedMs + "ms instead of the 1s Retry-After");
    }

    @Test
    void aLongRetryAfterPausesOtherCallsInsteadOfWaiting() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start();
        server.createContext("/api/v3/ticker", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "600");
            respond(exchange, 429, "{\"code\":-1003}");
        });
        server.createContext("/api/v3/time", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{}");
        });
        server.start();
        BinanceResilience resilience = new BinanceResilience(fast().withWeights(6000, 2400, Duration.ofMillis(200)));

        assertEquals(429, resilience.send(TICKER, BinanceCall.MARKET_DATA, get("/api/v3/ticker")).statusCode());
        BinanceRequestRejectedException rejected = assertThrows(BinanceRequestRejectedException.class,
                () -> resilience.send(TIME, BinanceCall.MARKET_DATA, get("/api/v3/time")));

        assertEquals(BinanceRequestRejectedException.Reason.RATE_LIMITED, rejected.reason());
        assertEquals(1, requests.get(), "600s is beyond max-retry-after: no retry, and the pause blocks other endpoints");
    }

    @Test
    void ipBanFailsFastUntilRetryAfterAndOpensTheBreaker() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "120");
            respond(exchange, 418, "{\"code\":-1003,\"msg\":\"banned\"}");
        });
        server.start();
        BinanceResilience resilience = new BinanceResilience(fast());

        assertEquals(418, resilience.send(TICKER, BinanceCall.MARKET_DATA, get("/api/v3/ticker")).statusCode());
        assertEquals(CircuitBreaker.State.OPEN, resilience.circuitBreaker(TICKER).getState());

        // Even an order on another endpoint is not sent while the ban lasts: it would only extend it
        BinanceRequestRejectedException rejected = assertThrows(BinanceRequestRejectedException.class,
                () -> resilience.send(BinanceEndpoint.spot("POST", "/api/v3/order", 1), BinanceCall.TRADING_WRITE,
                        get("/api/v3/order")));
        assertEquals(BinanceRequestRejectedException.Reason.IP_BANNED, rejected.reason());
        assertEquals(1, requests.get());
    }

    @Test
    void openBreakerRefusesMarketDataButLetsCriticalCallsThrough() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start();
        server.createContext("/api/v3/ticker", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{}");
        });
        server.start();
        BinanceResilience resilience = new BinanceResilience(fast());
        resilience.circuitBreaker(TICKER).transitionToOpenState();

        BinanceRequestRejectedException rejected = assertThrows(BinanceRequestRejectedException.class,
                () -> resilience.send(TICKER, BinanceCall.MARKET_DATA, get("/api/v3/ticker")));
        assertEquals(BinanceRequestRejectedException.Reason.CIRCUIT_OPEN, rejected.reason());
        assertEquals(0, requests.get());

        assertEquals(200, resilience.send(TICKER, BinanceCall.TRADING_WRITE, get("/api/v3/ticker")).statusCode());
        assertEquals(1, requests.get());
        assertTrue(resilience.isOpen(TICKER));
    }

    @Test
    void failuresOpenTheBreaker() throws Exception {
        start();
        server.createContext("/api/v3/ticker", exchange -> respond(exchange, 500, "{}"));
        server.start();
        BinanceResilience resilience = new BinanceResilience(BinanceResilienceSettings.defaults()
                .withRetry(0, Duration.ofMillis(10), Duration.ofMillis(10))
                .withBreaker(50f, 4, 4, Duration.ofMinutes(1)));

        for (int i = 0; i < 4; i++) {
            resilience.send(TICKER, BinanceCall.MARKET_DATA, get("/api/v3/ticker"));
        }

        assertTrue(resilience.isOpen(TICKER));
    }

    @Test
    void backtestHistoryDrawsFromTheSameWeightAsTheLiveBot() throws Exception {
        start();
        server.createContext("/", exchange -> respond(exchange, 200, "{}"));
        server.start();
        BinanceResilienceSettings settings = fast().withWeights(100, 100, Duration.ofMillis(300));
        // Two clients, one shared instance: the way engine-app wires the live pollers and the backtest loader
        BinanceResilience shared = new BinanceResilience(settings);
        BinanceEndpoint history = BinanceEndpoint.spot("GET", "/api/v3/klines", 30);

        shared.send(history, BinanceCall.HISTORY, get("/api/v3/klines"));
        shared.send(history, BinanceCall.HISTORY, get("/api/v3/klines"));

        assertEquals(40, shared.availableWeight(BinanceApi.SPOT, "localhost"));
        // 40 - 30 would dip into the 20% kept for the live bot: the backtest waits, then gives up
        BinanceRequestRejectedException rejected = assertThrows(BinanceRequestRejectedException.class,
                () -> shared.send(history, BinanceCall.HISTORY, get("/api/v3/klines")));
        assertEquals(BinanceRequestRejectedException.Reason.RATE_LIMITED, rejected.reason());
        // ...while the live bot still gets the remaining weight
        assertEquals(200, shared.send(history, BinanceCall.MARKET_DATA, get("/api/v3/klines")).statusCode());
        assertEquals(10, shared.availableWeight(BinanceApi.SPOT, "localhost"));
    }

    @Test
    void recordsTheUsedWeightHeader() throws Exception {
        start();
        server.createContext("/api/v3/time", exchange -> {
            exchange.getResponseHeaders().add("X-MBX-USED-WEIGHT-1M", "321");
            respond(exchange, 200, "{}");
        });
        server.start();
        BinanceResilience resilience = new BinanceResilience(fast());

        resilience.send(TIME, BinanceCall.TRADING_ONCE, get("/api/v3/time"));

        assertEquals(321, resilience.lastUsedWeight("localhost").orElseThrow());
        assertEquals(321, resilience.lastUsedWeights().get("localhost"));
    }

    @Test
    void usedWeightNearTheLimitHoldsNonCriticalCallsUntilTheNextMinute() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("X-MBX-USED-WEIGHT-1M", "95");
            respond(exchange, 200, "{}");
        });
        server.start();
        // A fixed clock mid-minute: the pause until the next minute (30s) is longer than the 100ms max wait
        BinanceResilience resilience = new BinanceResilience(fast().withWeights(100, 100, Duration.ofMillis(100)),
                java.net.http.HttpClient.newHttpClient(), () -> 1_800_030_000L);

        resilience.send(TIME, BinanceCall.TRADING_ONCE, get("/api/v3/time"));
        assertThrows(BinanceRequestRejectedException.class,
                () -> resilience.send(TICKER, BinanceCall.MARKET_DATA, get("/api/v3/ticker")));
        // An order or a stop is never held back by it
        assertEquals(200, resilience.send(TIME, BinanceCall.TRADING_ONCE, get("/api/v3/time")).statusCode());
        assertEquals(2, requests.get());
    }

    @Test
    void timeoutFailsTheAttemptAndIsNotRetriedForNewOrders() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start();
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        BinanceResilience resilience = new BinanceResilience(fast().withTimeouts(Duration.ofSeconds(1), Duration.ofMillis(300)));

        long started = System.nanoTime();
        assertThrows(HttpTimeoutException.class, () -> resilience.send(BinanceEndpoint.spot("POST", "/api/v3/order", 1),
                BinanceCall.TRADING_WRITE, get("/api/v3/order")));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertEquals(1, requests.get(), "a timed out order may have reached Binance: never resent");
        assertTrue(elapsedMs < 2_000, "took " + elapsedMs + "ms");

        assertThrows(HttpTimeoutException.class, () -> resilience.send(TICKER, BinanceCall.MARKET_DATA, get("/api/v3/ticker")));
        assertEquals(4, requests.get(), "idempotent reads retry a timeout: 1 + 2 retries");
    }

    @Test
    void newOrdersAreRetriedWhenTheConnectionNeverOpened() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        // Windows retries a refused localhost SYN for ~2s: the TimeLimiter must not fire first
        BinanceResilience resilience = new BinanceResilience(fast().withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(10)));
        BinanceEndpoint order = BinanceEndpoint.spot("POST", "/api/v3/order", 1);
        AtomicInteger builds = new AtomicInteger();

        IOException failure = assertThrows(IOException.class, () -> resilience.send(order, BinanceCall.TRADING_WRITE, () -> {
            builds.incrementAndGet();
            return HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + closedPort + "/api/v3/order"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
        }));

        assertEquals(3, builds.get(), "connection refused proves nothing was sent: retried, rebuilt each time; got " + failure);
    }
}
