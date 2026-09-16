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

public class BinanceSymbolValidationServiceTest {

    private static final BigDecimal PRICE = new BigDecimal("100000");

    private static final class FakeExecutor extends BinanceOrderExecutor {
        final SymbolFilters filters;

        FakeExecutor(SymbolFilters filters) {
            super("key", "secret", "http://localhost:1", 0, 10, 50);
            this.filters = filters;
        }

        @Override
        public SymbolFilters getSymbolFilters(String symbol) {
            return filters;
        }
    }

    // BTCUSDT-like: minQty 0.00001, step 0.00001, NOTIONAL min 5 USDT
    private static BinanceSymbolValidationService service() {
        return new BinanceSymbolValidationService(new FakeExecutor(new BinanceOrderExecutor.SymbolFilters(
                "BTCUSDT", new BigDecimal("5"), new BigDecimal("0.00001"), new BigDecimal("9000"),
                new BigDecimal("0.00001"))));
    }

    private HttpServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void keepsValidQuantityRoundedToStep() {
        Optional<BigDecimal> qty = service().validateAndAdjustQuantity("BTCUSDT", new BigDecimal("0.000505"), PRICE);

        assertEquals(0, new BigDecimal("0.0005").compareTo(qty.orElseThrow()));
    }

    @Test
    public void rejectsInsteadOfInflatingBeyondAllocatedCapital() {
        // 0.00003 BTC = 3 USDT, below the 5 USDT minimum, with only 3 USDT allocated
        Optional<BigDecimal> qty = service().validateAndAdjustQuantity(
                "BTCUSDT", new BigDecimal("0.00003"), PRICE, new BigDecimal("3"));

        assertTrue(qty.isEmpty());
    }

    @Test
    public void defaultCapIsTheRequestedNotional() {
        Optional<BigDecimal> qty = service().validateAndAdjustQuantity("BTCUSDT", new BigDecimal("0.00003"), PRICE);

        assertTrue(qty.isEmpty());
    }

    @Test
    public void raisesToMinimumWhenItFitsAllocatedCapital() {
        Optional<BigDecimal> qty = service().validateAndAdjustQuantity(
                "BTCUSDT", new BigDecimal("0.00003"), PRICE, new BigDecimal("10"));

        assertEquals(0, new BigDecimal("0.00005").compareTo(qty.orElseThrow()));
    }

    @Test
    public void executorReadsNotionalFilter() throws Exception {
        String body = """
                {"symbols":[{"symbol":"BTCUSDT","filters":[
                  {"filterType":"PRICE_FILTER","minPrice":"0.01","maxPrice":"1000000","tickSize":"0.01"},
                  {"filterType":"LOT_SIZE","minQty":"0.00001","maxQty":"9000","stepSize":"0.00001"},
                  {"filterType":"NOTIONAL","minNotional":"5.00000000","applyMinToMarket":true,
                   "maxNotional":"9000000.00000000","applyMaxToMarket":false,"avgPriceMins":5}
                ]}]}
                """;
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/exchangeInfo", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        BinanceOrderExecutor executor = new BinanceOrderExecutor("key", "secret",
                "http://localhost:" + server.getAddress().getPort(), 0, 10, 50);

        BinanceOrderExecutor.SymbolFilters filters = executor.getSymbolFilters("BTCUSDT");

        assertEquals(0, new BigDecimal("5").compareTo(filters.minNotional()));
    }
}
