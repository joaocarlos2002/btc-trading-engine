package dev.romeo.btctradingengine.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Issue #104: the central hook reports endpoint, status and the used weight header of each request. */
class HttpMetricsTest {

    private final List<String> calls = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        HttpMetrics.setListener(null);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reportsStatusEndpointAndUsedWeight() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/klines", exchange -> {
            exchange.getResponseHeaders().add("x-mbx-used-weight-1m", "42");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.start();
        HttpMetrics.setListener((host, endpoint, status, weight) ->
                calls.add(host + " " + endpoint + " " + status + " " + weight));

        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3/klines?symbol=BTCUSDT"))
                .GET().build();
        HttpResponse<String> response = HttpMetrics.send(HttpClient.newHttpClient(), request,
                HttpResponse.BodyHandlers.ofString());

        assertEquals(429, response.statusCode());
        assertEquals(List.of("127.0.0.1 /api/v3/klines 429 42"), calls);
    }

    @Test
    void reportsAnIoFailureAndRethrows() {
        HttpMetrics.setListener((host, endpoint, status, weight) -> calls.add(endpoint + " " + status));
        // Port 1 on loopback refuses the connection
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/fapi/v1/premiumIndex")).build();

        assertThrows(IOException.class, () -> HttpMetrics.send(HttpClient.newHttpClient(), request,
                HttpResponse.BodyHandlers.ofString()));
        assertEquals(List.of("/fapi/v1/premiumIndex " + HttpMetrics.IO_ERROR), calls);
    }

    @Test
    void aThrowingListenerDoesNotBreakTheRequestAndAnUnparsableWeightIsMinusOne() {
        assertEquals(-1, HttpMetrics.parseWeight("abc"));
        assertEquals(7, HttpMetrics.parseWeight(" 7 "));
        HttpMetrics.setListener((host, endpoint, status, weight) -> {
            throw new IllegalStateException("boom");
        });
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/x")).build();

        assertThrows(IOException.class, () -> HttpMetrics.send(HttpClient.newHttpClient(), request,
                HttpResponse.BodyHandlers.ofString()));
    }
}
