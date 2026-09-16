package dev.romeo.btctradingengine.trading;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BinanceOrderExecutorParseTest {

    // Real shape of a FULL response to POST /api/v3/order (MARKET BUY filled in two trades)
    private static final String FULL_ORDER_RESPONSE = """
            {"symbol":"BTCUSDT","orderId":28457,"orderListId":-1,"clientOrderId":"entry-abc",
             "transactTime":1757990400000,"price":"0.00000000","origQty":"0.00200000",
             "executedQty":"0.00200000","origQuoteOrderQty":"0.00000000","cummulativeQuoteQty":"230.00000000",
             "status":"FILLED","timeInForce":"GTC","type":"MARKET","side":"BUY","workingTime":1757990400000,
             "selfTradePreventionMode":"EXPIRE_MAKER",
             "fills":[
               {"price":"114990.00000000","qty":"0.00100000","commission":"0.00000100","commissionAsset":"BTC","tradeId":56},
               {"price":"115010.00000000","qty":"0.00100000","commission":"0.00000100","commissionAsset":"BTC","tradeId":57}
             ]}
            """;

    private HttpServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private BinanceOrderExecutor startServer(String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/order", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new BinanceOrderExecutor("key", "secret",
                "http://localhost:" + server.getAddress().getPort(), 0, 10, 50);
    }

    @Test
    public void fullOrderResponseYieldsPositiveAveragePrice() throws Exception {
        BinanceOrderExecutor.OrderResult result = startServer(FULL_ORDER_RESPONSE)
                .executeBuyMarket("BTCUSDT", new BigDecimal("0.002"), "entry-abc");

        assertTrue(result.success());
        assertEquals("28457", result.orderId());
        assertEquals(0, new BigDecimal("0.002").compareTo(result.executedQuantity()));
        assertTrue(result.averagePrice().signum() > 0);
        assertEquals(0, new BigDecimal("115000").compareTo(result.averagePrice()));
    }

    @Test
    public void queryOrderUsesSameParsing() throws Exception {
        Optional<BinanceOrderExecutor.QueriedOrder> order = startServer(FULL_ORDER_RESPONSE)
                .queryOrder("BTCUSDT", "entry-abc");

        assertTrue(order.isPresent());
        assertEquals("FILLED", order.get().status());
        assertEquals(0, new BigDecimal("115000").compareTo(order.get().averagePrice()));
    }
}
