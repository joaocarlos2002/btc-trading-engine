package dev.romeo.btctradingengine.trading;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static dev.romeo.btctradingengine.trading.BinanceFixtures.fill;
import static dev.romeo.btctradingengine.trading.BinanceWireMock.assertSigned;
import static dev.romeo.btctradingengine.trading.BinanceWireMock.params;
import static dev.romeo.btctradingengine.trading.BinanceWireMock.requests;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BinanceOrderExecutor over real HTTP against WireMock with Binance-shaped responses (issue #103): request
 * signing, response parsing, business errors, and the shared Resilience4j policies (retry, re-signing,
 * Retry-After, IP ban, timeouts) as seen on the wire. No Docker needed.
 */
class BinanceOrderExecutorIT {
    private static final String SYMBOL = "BTCUSDT";
    private static final String CLIENT_ID = "btce-BTCUSDT-POS_1-entry";
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(400);

    @RegisterExtension
    static WireMockExtension wm = BinanceWireMock.extension();

    @BeforeEach
    void serverTime() {
        BinanceWireMock.stubServerTime(wm);
    }

    /** One retry, 20ms backoff, a short request timeout so the slow-response tests stay fast. */
    private static BinanceOrderExecutor executor() {
        return new BinanceOrderExecutor(BinanceWireMock.API_KEY, BinanceWireMock.API_SECRET, wm.baseUrl(),
                1, 20, 50, Duration.ofSeconds(2), REQUEST_TIMEOUT);
    }

    private static String queriedFilled(String side, String quote) {
        return fill(BinanceFixtures.QUERY_ORDER_FILLED,
                Map.of("orderId", "4281766", "clientOrderId", CLIENT_ID, "quote", quote, "side", side));
    }

    // ---- new orders ----

    @Test
    void marketBuyIsSignedAndItsFillIsParsed() {
        wm.stubFor(post(urlPathEqualTo("/api/v3/order"))
                .willReturn(okJson(fill(BinanceFixtures.ORDER_BUY_FILLED, Map.of("clientOrderId", CLIENT_ID)))));

        BinanceOrderExecutor.OrderResult result = executor().executeBuyMarket(SYMBOL, new BigDecimal("0.00833000"), CLIENT_ID);

        assertTrue(result.success());
        assertEquals("4281765", result.orderId());
        assertEquals(0, new BigDecimal("0.00833").compareTo(result.executedQuantity()));
        // cummulativeQuoteQty / executedQty, not the first fill's price
        assertEquals(0, new BigDecimal("60010").compareTo(result.averagePrice()));

        List<LoggedRequest> posts = requests(wm, "POST", "/api/v3/order");
        assertEquals(1, posts.size());
        assertSigned(posts.getFirst());
        Map<String, String> params = params(posts.getFirst());
        assertEquals(SYMBOL, params.get("symbol"));
        assertEquals("BUY", params.get("side"));
        assertEquals("MARKET", params.get("type"));
        assertEquals("0.00833", params.get("quantity"));
        assertEquals(CLIENT_ID, params.get("newClientOrderId"));
        assertEquals(1, BinanceWireMock.serverTimeRequests(wm), "clock synced once before the first signed request");
    }

    @Test
    void acceptedButUnfilledOrderReportsZeroExecuted() {
        wm.stubFor(post(urlPathEqualTo("/api/v3/order"))
                .willReturn(okJson(fill(BinanceFixtures.ORDER_BUY_NEW, Map.of("clientOrderId", CLIENT_ID)))));

        BinanceOrderExecutor.OrderResult result = executor().executeBuyMarket(SYMBOL, new BigDecimal("0.00833"), CLIENT_ID);

        assertTrue(result.success());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.executedQuantity()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.averagePrice()));
    }

    @Test
    void insufficientBalanceFailsOnceWithoutRetry() {
        wm.stubFor(post(urlPathEqualTo("/api/v3/order"))
                .willReturn(aResponse().withStatus(400).withBody(BinanceFixtures.ERROR_INSUFFICIENT_BALANCE)));

        BinanceOrderExecutor.OrderResult result = executor().executeSellMarket(SYMBOL, new BigDecimal("0.00833"), CLIENT_ID);

        assertFalse(result.success());
        assertTrue(result.error().contains("-2010"), result.error());
        assertEquals(1, requests(wm, "POST", "/api/v3/order").size());
    }

    @Test
    void timestampRejectionResyncsTheClockBeforeTheNextSignedRequest() {
        wm.stubFor(post(urlPathEqualTo("/api/v3/order"))
                .willReturn(aResponse().withStatus(400).withBody(BinanceFixtures.ERROR_TIMESTAMP)));
        wm.stubFor(get(urlPathEqualTo("/api/v3/account"))
                .willReturn(okJson(fill(BinanceFixtures.ACCOUNT, Map.of("btc", "0.5", "usdt", "1000")))));
        BinanceOrderExecutor executor = executor();

        assertFalse(executor.executeBuyMarket(SYMBOL, new BigDecimal("0.00833"), CLIENT_ID).success());
        assertEquals(1, requests(wm, "POST", "/api/v3/order").size(), "a -1021 is not retried in place");
        assertTrue(executor.getBalance("USDT").success());

        assertEquals(2, BinanceWireMock.serverTimeRequests(wm));
    }

    @Test
    void rateLimitedOrderIsRetriedAfterRetryAfterAndReSigned() {
        wm.stubFor(post(urlPathEqualTo("/api/v3/order")).inScenario("429").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1").withBody(BinanceFixtures.ERROR_TOO_MANY_REQUESTS))
                .willSetStateTo("recovered"));
        wm.stubFor(post(urlPathEqualTo("/api/v3/order")).inScenario("429").whenScenarioStateIs("recovered")
                .willReturn(okJson(fill(BinanceFixtures.ORDER_BUY_FILLED, Map.of("clientOrderId", CLIENT_ID)))));

        long start = System.nanoTime();
        BinanceOrderExecutor.OrderResult result = executor().executeBuyMarket(SYMBOL, new BigDecimal("0.00833"), CLIENT_ID);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(result.success(), "Binance did not process a 429, so even a new order may be sent again");
        List<LoggedRequest> posts = requests(wm, "POST", "/api/v3/order");
        assertEquals(2, posts.size());
        assertTrue(elapsedMs >= 900, "waited for Retry-After: " + elapsedMs + "ms");
        posts.forEach(BinanceWireMock::assertSigned);
        assertNotEquals(params(posts.get(0)).get("timestamp"), params(posts.get(1)).get("timestamp"),
                "the retry carries a fresh timestamp");
        assertNotEquals(params(posts.get(0)).get("signature"), params(posts.get(1)).get("signature"));
        assertEquals(params(posts.get(0)).get("newClientOrderId"), params(posts.get(1)).get("newClientOrderId"));
    }

    @Test
    void serverErrorOnNewOrderIsNotResent() {
        wm.stubFor(post(urlPathEqualTo("/api/v3/order"))
                .willReturn(aResponse().withStatus(503).withBody(BinanceFixtures.ERROR_INTERNAL)));
        wm.stubFor(get(urlPathEqualTo("/api/v3/order"))
                .willReturn(aResponse().withStatus(400).withBody(BinanceFixtures.ERROR_ORDER_DOES_NOT_EXIST)));

        BinanceOrderExecutor.OrderResult result = executor().executeBuyMarket(SYMBOL, new BigDecimal("0.00833"), CLIENT_ID);

        assertFalse(result.success());
        // A 5xx means "execution status unknown": resending could buy twice
        assertEquals(1, requests(wm, "POST", "/api/v3/order").size());
    }

    @Test
    void serverErrorOnNewOrderThatDidFillIsRecoveredByClientOrderId() {
        // Binance documents a 5xx as "execution status UNKNOWN": the MARKET buy may have filled anyway
        wm.stubFor(post(urlPathEqualTo("/api/v3/order"))
                .willReturn(aResponse().withStatus(503).withBody(BinanceFixtures.ERROR_INTERNAL)));
        wm.stubFor(get(urlPathEqualTo("/api/v3/order")).withQueryParam("origClientOrderId", equalTo(CLIENT_ID))
                .willReturn(okJson(queriedFilled("BUY", "499.88330000"))));

        BinanceOrderExecutor.OrderResult result = executor().executeBuyMarket(SYMBOL, new BigDecimal("0.00833"), CLIENT_ID);

        assertTrue(result.success(), "a filled order must not be reported as failed: the bot would lose the bought BTC");
        assertEquals(0, new BigDecimal("0.00833").compareTo(result.executedQuantity()));
        assertEquals(1, requests(wm, "POST", "/api/v3/order").size());
    }

    @Test
    void slowNewOrderTimesOutIsNotResentAndIsRecoveredByClientOrderId() {
        wm.stubFor(post(urlPathEqualTo("/api/v3/order"))
                .willReturn(okJson(fill(BinanceFixtures.ORDER_BUY_FILLED, Map.of("clientOrderId", CLIENT_ID)))
                        .withFixedDelay((int) REQUEST_TIMEOUT.toMillis() * 4)));
        wm.stubFor(get(urlPathEqualTo("/api/v3/order")).withQueryParam("origClientOrderId", equalTo(CLIENT_ID))
                .willReturn(okJson(queriedFilled("BUY", "499.88330000"))));

        BinanceOrderExecutor.OrderResult result = executor().executeBuyMarket(SYMBOL, new BigDecimal("0.00833"), CLIENT_ID);

        assertTrue(result.success());
        assertEquals("4281766", result.orderId());
        assertEquals(0, new BigDecimal("60010").compareTo(result.averagePrice()));
        assertEquals(1, requests(wm, "POST", "/api/v3/order").size(), "a timed-out order is ambiguous, never resent");
        List<LoggedRequest> lookups = requests(wm, "GET", "/api/v3/order");
        assertEquals(1, lookups.size());
        assertSigned(lookups.getFirst());
    }

    // ---- reads ----

    @Test
    void orderLookupTellsFoundNotFoundAndErrorApart() {
        wm.stubFor(get(urlPathEqualTo("/api/v3/order")).withQueryParam("origClientOrderId", equalTo("found"))
                .willReturn(okJson(queriedFilled("SELL", "491.55330000"))));
        wm.stubFor(get(urlPathEqualTo("/api/v3/order")).withQueryParam("origClientOrderId", equalTo("unknown"))
                .willReturn(aResponse().withStatus(400).withBody(BinanceFixtures.ERROR_ORDER_DOES_NOT_EXIST)));
        wm.stubFor(get(urlPathEqualTo("/api/v3/order")).withQueryParam("origClientOrderId", equalTo("broken"))
                .willReturn(aResponse().withStatus(500).withBody(BinanceFixtures.ERROR_INTERNAL)));
        BinanceOrderExecutor executor = executor();

        BinanceOrderExecutor.OrderLookup found = executor.lookupOrder(SYMBOL, "found");
        assertEquals(BinanceOrderExecutor.OrderListQuery.State.FOUND, found.state());
        assertEquals("FILLED", found.order().orElseThrow().status());
        assertEquals(0, new BigDecimal("59010").compareTo(found.order().orElseThrow().averagePrice()));
        assertEquals(SYMBOL, params(requests(wm, "GET", "/api/v3/order").getFirst()).get("symbol"));

        assertEquals(BinanceOrderExecutor.OrderListQuery.State.NOT_FOUND, executor.lookupOrder(SYMBOL, "unknown").state());

        BinanceOrderExecutor.OrderLookup broken = executor.lookupOrder(SYMBOL, "broken");
        assertEquals(BinanceOrderExecutor.OrderListQuery.State.ERROR, broken.state());
        long brokenRequests = requests(wm, "GET", "/api/v3/order").stream()
                .filter(r -> "broken".equals(params(r).get("origClientOrderId"))).count();
        assertEquals(2, brokenRequests, "a read is retried on 5xx (1 retry configured)");
    }

    @Test
    void rateLimitedReadIsRetriedWithAFreshSignature() {
        wm.stubFor(get(urlPathEqualTo("/api/v3/account")).inScenario("read429").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1").withBody(BinanceFixtures.ERROR_TOO_MANY_REQUESTS))
                .willSetStateTo("ok"));
        wm.stubFor(get(urlPathEqualTo("/api/v3/account")).inScenario("read429").whenScenarioStateIs("ok")
                .willReturn(okJson(fill(BinanceFixtures.ACCOUNT, Map.of("btc", "0.00833", "usdt", "500.12")))));

        BinanceOrderExecutor.BalanceResult usdt = executor().getBalance("USDT");

        assertTrue(usdt.success());
        assertEquals(0, new BigDecimal("500.12").compareTo(usdt.total()));
        List<LoggedRequest> calls = requests(wm, "GET", "/api/v3/account");
        assertEquals(2, calls.size());
        calls.forEach(BinanceWireMock::assertSigned);
        assertNotEquals(params(calls.get(0)).get("signature"), params(calls.get(1)).get("signature"));
    }

    @Test
    void slowReadTimesOutAndIsRetried() {
        wm.stubFor(get(urlPathEqualTo("/api/v3/account")).inScenario("slow").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(fill(BinanceFixtures.ACCOUNT, Map.of("btc", "1", "usdt", "1")))
                        .withFixedDelay((int) REQUEST_TIMEOUT.toMillis() * 4))
                .willSetStateTo("fast"));
        wm.stubFor(get(urlPathEqualTo("/api/v3/account")).inScenario("slow").whenScenarioStateIs("fast")
                .willReturn(okJson(fill(BinanceFixtures.ACCOUNT, Map.of("btc", "0.25", "usdt", "1")))));

        BinanceOrderExecutor.BalanceResult btc = executor().getBalance("BTC");

        assertTrue(btc.success());
        assertEquals(0, new BigDecimal("0.25").compareTo(btc.free()));
        assertEquals(2, requests(wm, "GET", "/api/v3/account").size());
    }

    @Test
    void ipBanFailsFastUntilItEndsWithoutSendingOrders() {
        wm.stubFor(get(urlPathEqualTo("/api/v3/account"))
                .willReturn(aResponse().withStatus(418).withHeader("Retry-After", "120").withBody(BinanceFixtures.ERROR_IP_BANNED)));
        wm.stubFor(post(urlPathEqualTo("/api/v3/order"))
                .willReturn(okJson(fill(BinanceFixtures.ORDER_BUY_FILLED, Map.of("clientOrderId", CLIENT_ID)))));
        BinanceOrderExecutor executor = executor();

        BinanceOrderExecutor.BalanceResult banned = executor.getBalance("USDT");
        assertFalse(banned.success());
        assertTrue(banned.error().contains("-1003"), banned.error());
        assertEquals(1, requests(wm, "GET", "/api/v3/account").size(), "a 418 is never retried");

        BinanceOrderExecutor.BalanceResult again = executor.getBalance("USDT");
        assertFalse(again.success());
        assertTrue(again.error().contains("IP ban"), again.error());
        assertFalse(executor.executeBuyMarket(SYMBOL, new BigDecimal("0.00833"), null).success());

        assertEquals(1, requests(wm, "GET", "/api/v3/account").size());
        assertEquals(0, requests(wm, "POST", "/api/v3/order").size(), "nothing reaches Binance during the ban");
    }

    @Test
    void exchangeInfoAccountAndOpenOrdersAreParsed() {
        wm.stubFor(get(urlPathEqualTo("/api/v3/exchangeInfo")).withQueryParam("symbol", equalTo(SYMBOL))
                .willReturn(okJson(BinanceFixtures.EXCHANGE_INFO_BTCUSDT)));
        wm.stubFor(get(urlPathEqualTo("/api/v3/account"))
                .willReturn(okJson(fill(BinanceFixtures.ACCOUNT, Map.of("btc", "0.00832167", "usdt", "500.11670000")))));
        wm.stubFor(get(urlPathEqualTo("/api/v3/openOrders")).willReturn(okJson(BinanceFixtures.OPEN_ORDERS)));
        BinanceOrderExecutor executor = executor();

        BinanceOrderExecutor.SymbolFilters filters = executor.getSymbolFilters(SYMBOL);
        assertEquals(0, new BigDecimal("5").compareTo(filters.minNotional()), "NOTIONAL filter");
        assertEquals(0, new BigDecimal("0.00001").compareTo(filters.stepSize()));
        assertEquals(0, new BigDecimal("0.00001").compareTo(filters.minQty()));
        assertEquals(0, new BigDecimal("9000").compareTo(filters.maxQty()));
        assertEquals(0, new BigDecimal("0.01").compareTo(filters.tickSize()));
        assertTrue(requests(wm, "GET", "/api/v3/exchangeInfo").getFirst().getHeader("X-MBX-APIKEY") == null,
                "a public endpoint is not signed");

        BinanceOrderExecutor.BalanceResult btc = executor.getBalance("BTC");
        assertEquals(0, new BigDecimal("0.00832167").compareTo(btc.free()));
        assertFalse(executor.getBalance("ETH").success());

        List<BinanceOrderExecutor.OpenOrder> open = executor.getOpenOrders(SYMBOL);
        assertEquals(List.of("btce-BTCUSDT-POS_9-sl", "web_7f3a2b"), open.stream().map(BinanceOrderExecutor.OpenOrder::clientOrderId).toList());
        assertEquals(0, new BigDecimal("59000").compareTo(open.getFirst().price()));
        assertSigned(requests(wm, "GET", "/api/v3/openOrders").getFirst());
    }

    // ---- OCO order lists ----

    @Test
    void ocoIsPlacedQueriedAndCancelledThroughOrderListEndpoints() {
        String list = "btce-BTCUSDT-POS_1-oco";
        String executing = fill(BinanceFixtures.ORDER_LIST_EXECUTING, Map.of("listClientOrderId", list));
        wm.stubFor(post(urlPathEqualTo("/api/v3/orderList/oco")).willReturn(okJson(executing)));
        wm.stubFor(get(urlPathEqualTo("/api/v3/orderList")).withQueryParam("origClientOrderId", equalTo(list))
                .willReturn(okJson(executing)));
        wm.stubFor(delete(urlPathEqualTo("/api/v3/orderList"))
                .willReturn(aResponse().withStatus(400).withBody(BinanceFixtures.ERROR_UNKNOWN_ORDER)));
        BinanceOrderExecutor executor = executor();

        BinanceOrderExecutor.OcoResult placed = executor.placeOcoSell(SYMBOL, new BigDecimal("0.00833"),
                new BigDecimal("60910.15000"), new BigDecimal("59709.90"), new BigDecimal("59650.19"),
                list, "btce-BTCUSDT-POS_1-tp", "btce-BTCUSDT-POS_1-sl");
        assertTrue(placed.success());
        assertEquals(1929, placed.orderListId());
        assertEquals("EXECUTING", placed.listOrderStatus());

        LoggedRequest oco = requests(wm, "POST", "/api/v3/orderList/oco").getFirst();
        assertSigned(oco);
        Map<String, String> params = params(oco);
        assertEquals("SELL", params.get("side"));
        assertEquals("LIMIT_MAKER", params.get("aboveType"));
        assertEquals("60910.15", params.get("abovePrice"));
        assertEquals("STOP_LOSS_LIMIT", params.get("belowType"));
        assertEquals("59709.9", params.get("belowStopPrice"));
        assertEquals("59650.19", params.get("belowPrice"));
        assertEquals("GTC", params.get("belowTimeInForce"));
        assertEquals(list, params.get("listClientOrderId"));
        assertEquals("btce-BTCUSDT-POS_1-tp", params.get("aboveClientOrderId"));

        assertTrue(executor.queryOrderList(list).isExecuting());
        BinanceOrderExecutor.OrderListQuery cancel = executor.cancelOrderList(SYMBOL, list);
        assertEquals(BinanceOrderExecutor.OrderListQuery.State.NOT_FOUND, cancel.state());
        assertEquals(list, params(requests(wm, "DELETE", "/api/v3/orderList").getFirst()).get("listClientOrderId"));
    }
}
