package dev.romeo.btctradingengine.trading;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #99: the OCO, order list query/cancel and PRICE_FILTER requests as Binance receives them. */
public class BinanceOrderExecutorOcoTest {

    private HttpServer server;
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private BinanceOrderExecutor start(String path, int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path, exchange -> {
            methods.add(exchange.getRequestMethod());
            queries.add(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new BinanceOrderExecutor("key", "secret", "http://localhost:" + server.getAddress().getPort(), 0, 10, 50);
    }

    private static Map<String, String> params(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return params;
    }

    private static void assertSigned(String query) throws Exception {
        int index = query.lastIndexOf("&signature=");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(query.substring(0, index).getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, query.substring(index + "&signature=".length()));
    }

    @Test
    public void placesSellOcoWithBothLegs() throws Exception {
        BinanceOrderExecutor executor = start("/api/v3/orderList/oco", 200,
                "{\"orderListId\":7,\"listOrderStatus\":\"EXECUTING\",\"listClientOrderId\":\"btce-BTCUSDT-POS_1-oco\"}");

        BinanceOrderExecutor.OcoResult result = executor.placeOcoSell("BTCUSDT", new BigDecimal("0.00049000"),
                new BigDecimal("102000.380"), new BigDecimal("98500.36"), new BigDecimal("98401.85"),
                "btce-BTCUSDT-POS_1-oco", "btce-BTCUSDT-POS_1-tp", "btce-BTCUSDT-POS_1-sl");

        assertTrue(result.success());
        assertEquals(7, result.orderListId());
        assertEquals(List.of("POST"), methods);
        Map<String, String> params = params(queries.getFirst());
        assertEquals("BTCUSDT", params.get("symbol"));
        assertEquals("SELL", params.get("side"));
        assertEquals("0.00049", params.get("quantity"));
        assertEquals("btce-BTCUSDT-POS_1-oco", params.get("listClientOrderId"));
        assertEquals("LIMIT_MAKER", params.get("aboveType"));
        assertEquals("102000.38", params.get("abovePrice"));
        assertEquals("btce-BTCUSDT-POS_1-tp", params.get("aboveClientOrderId"));
        assertEquals("STOP_LOSS_LIMIT", params.get("belowType"));
        assertEquals("98500.36", params.get("belowStopPrice"));
        assertEquals("98401.85", params.get("belowPrice"));
        assertEquals("GTC", params.get("belowTimeInForce"));
        assertEquals("btce-BTCUSDT-POS_1-sl", params.get("belowClientOrderId"));
        assertTrue(params.containsKey("timestamp"));
        assertSigned(queries.getFirst());
    }

    @Test
    public void rejectedOcoIsAFailure() throws Exception {
        BinanceOrderExecutor executor = start("/api/v3/orderList/oco", 400,
                "{\"code\":-2010,\"msg\":\"Account has insufficient balance for requested action.\"}");

        BinanceOrderExecutor.OcoResult result = executor.placeOcoSell("BTCUSDT", BigDecimal.ONE, BigDecimal.TEN,
                BigDecimal.ONE, BigDecimal.ONE, "l", "t", "s");

        assertFalse(result.success());
        assertTrue(result.error().contains("-2010"));
    }

    @Test
    public void cancelsOrderListByClientId() throws Exception {
        BinanceOrderExecutor executor = start("/api/v3/orderList", 200,
                "{\"orderListId\":7,\"listOrderStatus\":\"ALL_DONE\"}");

        BinanceOrderExecutor.OrderListQuery result = executor.cancelOrderList("BTCUSDT", "btce-BTCUSDT-POS_1-oco");

        assertEquals(BinanceOrderExecutor.OrderListQuery.State.FOUND, result.state());
        assertEquals(List.of("DELETE"), methods);
        Map<String, String> params = params(queries.getFirst());
        assertEquals("BTCUSDT", params.get("symbol"));
        assertEquals("btce-BTCUSDT-POS_1-oco", params.get("listClientOrderId"));
        assertSigned(queries.getFirst());
    }

    @Test
    public void cancelOfAnOrderListNoLongerOpenIsNotFound() throws Exception {
        BinanceOrderExecutor executor = start("/api/v3/orderList", 400, "{\"code\":-2011,\"msg\":\"Unknown order sent.\"}");

        assertEquals(BinanceOrderExecutor.OrderListQuery.State.NOT_FOUND,
                executor.cancelOrderList("BTCUSDT", "btce-BTCUSDT-POS_1-oco").state());
    }

    @Test
    public void otherErrorsAreNotMistakenForNotFound() throws Exception {
        BinanceOrderExecutor executor = start("/api/v3/orderList", 400, "{\"code\":-1021,\"msg\":\"Timestamp outside recvWindow.\"}");

        assertEquals(BinanceOrderExecutor.OrderListQuery.State.ERROR,
                executor.cancelOrderList("BTCUSDT", "btce-BTCUSDT-POS_1-oco").state());
    }

    @Test
    public void queriesOrderListByOriginalClientId() throws Exception {
        BinanceOrderExecutor executor = start("/api/v3/orderList", 200,
                "{\"orderListId\":7,\"listStatusType\":\"EXEC_STARTED\",\"listOrderStatus\":\"EXECUTING\"}");

        BinanceOrderExecutor.OrderListQuery result = executor.queryOrderList("btce-BTCUSDT-POS_1-oco");

        assertTrue(result.isExecuting());
        assertEquals(List.of("GET"), methods);
        assertEquals("btce-BTCUSDT-POS_1-oco", params(queries.getFirst()).get("origClientOrderId"));
    }

    @Test
    public void parsesPriceFilterTickSize() throws Exception {
        BinanceOrderExecutor executor = start("/api/v3/exchangeInfo", 200, """
                {"symbols":[{"symbol":"BTCUSDT","filters":[
                  {"filterType":"PRICE_FILTER","minPrice":"0.01","maxPrice":"1000000.00","tickSize":"0.01"},
                  {"filterType":"LOT_SIZE","minQty":"0.00001","maxQty":"9000","stepSize":"0.00001"},
                  {"filterType":"NOTIONAL","minNotional":"5.00"}]}]}
                """);

        BinanceOrderExecutor.SymbolFilters filters = executor.getSymbolFilters("BTCUSDT");

        assertEquals(0, new BigDecimal("0.01").compareTo(filters.tickSize()));
        assertEquals(0, new BigDecimal("0.00001").compareTo(filters.stepSize()));
    }
}
