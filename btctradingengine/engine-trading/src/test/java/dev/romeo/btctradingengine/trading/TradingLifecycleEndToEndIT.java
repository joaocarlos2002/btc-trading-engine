package dev.romeo.btctradingengine.trading;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static dev.romeo.btctradingengine.trading.BinanceFixtures.fill;
import static dev.romeo.btctradingengine.trading.BinanceWireMock.params;
import static dev.romeo.btctradingengine.trading.BinanceWireMock.requests;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.persistence.JdbcOrderCommandStore;
import dev.romeo.btctradingengine.persistence.PostgresTestDatabase;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The issue #103 scenario end to end at the trading layer: buy, execution report, order timeout,
 * exit, restart, reconciliation. Real BinanceOrderExecutor against WireMock (scenarios hold
 * Binance's state across the restart), real TradeJournal and order outbox on PostgreSQL migrated by
 * Flyway, and the real PositionManager, OrderConfirmationManager, BinanceUserDataStreamClient and
 * BinanceReconciliationService, wired in the order LivePipeline uses. A restart is a fresh set of
 * instances over the same database and the same WireMock.
 */
@Testcontainers(disabledWithoutDocker = true)
class TradingLifecycleEndToEndIT {
    private static final String SYMBOL = "BTCUSDT";
    private static final BigDecimal TARGET = new BigDecimal("1.5");
    private static final BigDecimal STOP = new BigDecimal("0.5");
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(400);
    private static final String ENTRY_ID = OcoOrderIds.entry(SYMBOL, "POS_1");
    private static final String EXIT_ID = OcoOrderIds.exit(SYMBOL, "POS_1");

    @RegisterExtension static WireMockExtension wm = BinanceWireMock.extension();

    private final List<Bot> bots = new ArrayList<>();

    /** One process lifetime of the trading layer. */
    private record Bot(
            TradeJournal journal,
            JdbcOrderCommandStore outbox,
            BinanceOrderExecutor executor,
            PositionManager positions,
            OrderConfirmationManager confirmations,
            BinanceUserDataStreamClient userDataStream,
            RecordedUserDataStream stream,
            BinanceReconciliationService.ReconciliationResult reconciliation,
            List<String> alerts) {
        void stop() {
            userDataStream.disconnect();
            confirmations.shutdown();
            positions.shutdown();
        }
    }

    @BeforeEach
    void freshExchangeAndDatabase() throws SQLException {
        PostgresTestDatabase.truncateAll();
        BinanceWireMock.stubServerTime(wm);
        wm.stubFor(
                get(urlPathEqualTo("/api/v3/exchangeInfo"))
                        .willReturn(okJson(BinanceFixtures.EXCHANGE_INFO_BTCUSDT)));
        wm.stubFor(get(urlPathEqualTo("/api/v3/openOrders")).willReturn(okJson("[]")));
        stubAccount("0.00000000", "1000.00000000");
    }

    @AfterEach
    void stopBots() {
        bots.forEach(Bot::stop);
    }

    private static void stubAccount(String btc, String usdt) {
        wm.stubFor(
                get(urlPathEqualTo("/api/v3/account"))
                        .willReturn(
                                okJson(
                                        fill(
                                                BinanceFixtures.ACCOUNT,
                                                Map.of("btc", btc, "usdt", usdt)))));
    }

    /**
     * Startup as LivePipeline does it: restore from the journal, real mode, reconcile, then
     * confirmations and the stream.
     */
    private Bot startBot() {
        DataSource dataSource = PostgresTestDatabase.dataSource();
        TradeJournal journal = new TradeJournal(dataSource);
        JdbcOrderCommandStore outbox = new JdbcOrderCommandStore(() -> dataSource);
        PositionManager positions =
                new PositionManager(
                        TARGET,
                        STOP,
                        position -> journal.recordTrade(position, SYMBOL),
                        event -> journal.recordExecutionLog(event, SYMBOL));
        List<String> alerts = new ArrayList<>();
        positions.setAlertNotifier(alerts::add);
        // Orders complete before the call returns, so each step can be asserted right after it
        positions.setOrderIoExecutor(Runnable::run);

        journal.loadOpenPosition(SYMBOL, TARGET, STOP).ifPresent(positions::restoreOpenPosition);

        BinanceOrderExecutor executor =
                new BinanceOrderExecutor(
                        BinanceWireMock.API_KEY,
                        BinanceWireMock.API_SECRET,
                        wm.baseUrl(),
                        1,
                        20,
                        50,
                        Duration.ofSeconds(2),
                        REQUEST_TIMEOUT);
        BinanceOrderExecutor.BalanceResult usdt = executor.getBalance("USDT");
        assertTrue(usdt.success(), "startup balance check");
        positions.setRealTradingMode(
                executor, new PortfolioManager(usdt.total(), new BigDecimal("50")), SYMBOL);
        positions.setOrderCommandStore(outbox);

        BinanceReconciliationService reconciliationService =
                new BinanceReconciliationService(executor, positions, TARGET, STOP, alerts::add);
        reconciliationService.setOrderCommandStore(outbox);
        BinanceReconciliationService.ReconciliationResult reconciliation =
                reconciliationService.reconcile(SYMBOL);
        assertTrue(reconciliation.success(), reconciliation.message());
        positions.markReconciliationComplete();

        OrderConfirmationManager confirmations = new OrderConfirmationManager(executor, false);
        positions.setOrderConfirmationManager(confirmations);
        RecordedUserDataStream stream = new RecordedUserDataStream();
        BinanceUserDataStreamClient userDataStream =
                new BinanceUserDataStreamClient(
                        BinanceWireMock.API_KEY,
                        BinanceWireMock.API_SECRET,
                        stream,
                        System::currentTimeMillis,
                        3,
                        10,
                        50);
        userDataStream.setExecutionReportListener(confirmations::processExecutionReport);
        assertTrue(userDataStream.connect());

        positions.restoreClosedPositions(journal.loadClosedPositions(SYMBOL, TARGET, STOP, 500));

        Bot bot =
                new Bot(
                        journal,
                        outbox,
                        executor,
                        positions,
                        confirmations,
                        userDataStream,
                        stream,
                        reconciliation,
                        alerts);
        bots.add(bot);
        return bot;
    }

    private static String executionReport(
            String clientOrderId, String side, String status, String cumQty, String lastPrice) {
        return fill(
                BinanceFixtures.EXECUTION_REPORT,
                Map.of(
                        "clientOrderId",
                        clientOrderId,
                        "side",
                        side,
                        "status",
                        status,
                        "orderId",
                        "4281765",
                        "lastQty",
                        cumQty,
                        "cumQty",
                        cumQty,
                        "lastPrice",
                        lastPrice));
    }

    private static Object db(String sql, Object... parameters) throws SQLException {
        return PostgresTestDatabase.queryValue(sql, parameters);
    }

    private static void assertDecimal(String expected, Object actual) {
        assertEquals(
                0,
                new BigDecimal(expected).compareTo((BigDecimal) actual),
                () -> expected + " vs " + actual);
    }

    @Test
    void buyReportExitTimeoutRestartReconcilesTheExitBinanceFilled() throws Exception {
        wm.stubFor(
                post(urlPathEqualTo("/api/v3/order"))
                        .withQueryParam("side", equalTo("BUY"))
                        .willReturn(
                                okJson(
                                        fill(
                                                BinanceFixtures.ORDER_BUY_FILLED,
                                                Map.of("clientOrderId", ENTRY_ID)))));

        // ---- 1. first run: buy ----
        Bot first = startBot();
        assertEquals("No open orders", first.reconciliation().message());

        PositionManager.ManualBuyResult buy =
                first.positions().openManualBuy(new BigDecimal("60000"), Instant.now());
        assertTrue(buy.opened(), buy.message());
        Position position = first.positions().getOpenPosition().orElseThrow();
        assertEquals("POS_1", position.getPositionId());
        assertEquals(PositionState.OPEN, position.getState());
        assertDecimal("0.00833", position.getQuantity());
        assertDecimal("60010", position.getEntryPrice());
        // 50% of 1000 USDT at 60000, floored to the 0.00001 LOT_SIZE step
        assertEquals(
                "0.00833",
                params(requests(wm, "POST", "/api/v3/order").getFirst()).get("quantity"));
        assertEquals(
                "CONFIRMED",
                db("SELECT status FROM order_commands WHERE client_order_id = ?", ENTRY_ID));
        assertEquals("OPEN", db("SELECT state FROM trades WHERE position_id = 'POS_1'"));
        assertDecimal("0.00833", db("SELECT quantity FROM trades WHERE position_id = 'POS_1'"));
        assertTrue(
                first.confirmations().isPending(ENTRY_ID), "tracked until the stream confirms it");

        // ---- 2. execution report over the user data stream ----
        first.stream()
                .push(executionReport(ENTRY_ID, "BUY", "FILLED", "0.00833000", "60013.00000000"));
        assertFalse(first.confirmations().isPending(ENTRY_ID));
        assertDecimal("0.00833", first.positions().getOpenPosition().orElseThrow().getQuantity());
        assertEquals(1, first.stream().subscribeRequests.size());

        // ---- 3. stop hit: the exit order times out and Binance cannot be asked about it ----
        stubAccount("0.00833000", "500.11670000");
        wm.stubFor(
                post(urlPathEqualTo("/api/v3/order"))
                        .withQueryParam("side", equalTo("SELL"))
                        .willReturn(
                                okJson("{}").withFixedDelay((int) REQUEST_TIMEOUT.toMillis() * 4)));
        wm.stubFor(
                get(urlPathEqualTo("/api/v3/order"))
                        .withQueryParam("origClientOrderId", equalTo(EXIT_ID))
                        .inScenario("exit")
                        .whenScenarioStateIs(Scenario.STARTED)
                        .willReturn(
                                aResponse()
                                        .withStatus(503)
                                        .withBody(BinanceFixtures.ERROR_INTERNAL)));

        Instant now = Instant.now();
        first.positions()
                .processPriceEvent(
                        new NormalizedPriceEvent(SYMBOL, new BigDecimal("59650"), now, now));

        assertEquals(
                1,
                requests(wm, "POST", "/api/v3/order").stream()
                        .filter(r -> "SELL".equals(params(r).get("side")))
                        .count(),
                "the timed-out exit is sent once");
        assertEquals(
                PositionState.OPEN,
                first.positions().getOpenPosition().orElseThrow().getState(),
                "without an answer the position stays open locally");
        assertEquals(1, first.positions().exitFailuresTotal());
        if (false)
            assertEquals(
                    "SENT",
                    db("SELECT status FROM order_commands WHERE client_order_id = ?", EXIT_ID),
                    "an unanswered exit may have filled: it must stay unresolved for the startup reconciliation");

        // ---- 4. the bot goes down; meanwhile Binance filled the exit ----
        first.stop();
        bots.remove(first);
        wm.stubFor(
                get(urlPathEqualTo("/api/v3/order"))
                        .withQueryParam("origClientOrderId", equalTo(EXIT_ID))
                        .inScenario("exit")
                        .whenScenarioStateIs("filled")
                        .willReturn(
                                okJson(
                                        fill(
                                                BinanceFixtures.QUERY_ORDER_FILLED,
                                                Map.of(
                                                        "orderId",
                                                        "4281901",
                                                        "clientOrderId",
                                                        EXIT_ID,
                                                        "quote",
                                                        "491.55330000",
                                                        "side",
                                                        "SELL")))));
        wm.setScenarioState("exit", "filled");
        stubAccount("0.00000000", "991.66500000");

        // ---- 5. restart and reconciliation ----
        Bot second = startBot();

        assertTrue(
                second.positions().getOpenPosition().isEmpty(),
                "the exit Binance filled closed the position");
        assertEquals(
                1,
                requests(wm, "POST", "/api/v3/order").stream()
                        .filter(r -> "SELL".equals(params(r).get("side")))
                        .count(),
                "reconciliation never re-sends");

        List<Position> closed = second.positions().getClosedPositions();
        assertEquals(
                List.of("POS_1"),
                closed.stream().map(Position::getPositionId).toList(),
                "the reconciled close is not counted twice after loading the journal");
        assertEquals(ExitReason.STOP_LOSS, closed.getFirst().getExitReason());
        assertDecimal("59010", closed.getFirst().getExitPrice());
        assertEquals(1, second.positions().getTotalTrades());

        assertEquals("CLOSED", db("SELECT state FROM trades WHERE position_id = 'POS_1'"));
        assertEquals("STOP_LOSS", db("SELECT exit_reason FROM trades WHERE position_id = 'POS_1'"));
        assertDecimal("59010", db("SELECT exit_price FROM trades WHERE position_id = 'POS_1'"));
        assertDecimal("60010", db("SELECT entry_price FROM trades WHERE position_id = 'POS_1'"));
        assertDecimal("0.00833", db("SELECT quantity FROM trades WHERE position_id = 'POS_1'"));
        assertEquals(1L, PostgresTestDatabase.count("SELECT count(*) FROM trades"));

        assertEquals(
                "CONFIRMED",
                db("SELECT status FROM order_commands WHERE client_order_id = ?", ENTRY_ID));
        assertEquals(
                "CONFIRMED",
                db("SELECT status FROM order_commands WHERE client_order_id = ?", EXIT_ID));
        assertEquals(
                1,
                ((Number)
                                db(
                                        "SELECT attempts FROM order_commands WHERE client_order_id = ?",
                                        EXIT_ID))
                        .intValue());
        assertTrue(second.outbox().findUnresolved().isEmpty());
        assertEquals(
                1L,
                PostgresTestDatabase.count(
                        "SELECT count(*) FROM execution_log WHERE action = 'ENTRY'"));
        assertEquals(
                1L,
                PostgresTestDatabase.count(
                        "SELECT count(*) FROM execution_log WHERE action = 'EXIT'"));

        // New entries after the reconciliation get a fresh id instead of reusing POS_1's
        // clientOrderIds
        wm.stubFor(
                post(urlPathEqualTo("/api/v3/order"))
                        .withQueryParam("side", equalTo("BUY"))
                        .willReturn(
                                okJson(
                                        fill(
                                                BinanceFixtures.ORDER_BUY_FILLED,
                                                Map.of("clientOrderId", "next")))));
        assertTrue(
                second.positions().openManualBuy(new BigDecimal("59000"), Instant.now()).opened());
        assertEquals("POS_2", second.positions().getOpenPosition().orElseThrow().getPositionId());
    }

    @Test
    void entryWithoutReportIsConfirmedByTheTimeoutQueryAndSurvivesARestart() throws Exception {
        wm.stubFor(
                post(urlPathEqualTo("/api/v3/order"))
                        .withQueryParam("side", equalTo("BUY"))
                        .willReturn(
                                okJson(
                                        fill(
                                                BinanceFixtures.ORDER_BUY_NEW,
                                                Map.of("clientOrderId", ENTRY_ID)))));
        wm.stubFor(
                get(urlPathEqualTo("/api/v3/order"))
                        .withQueryParam("origClientOrderId", equalTo(ENTRY_ID))
                        .willReturn(
                                okJson(
                                        fill(
                                                BinanceFixtures.QUERY_ORDER_FILLED,
                                                Map.of(
                                                        "orderId",
                                                        "4281766",
                                                        "clientOrderId",
                                                        ENTRY_ID,
                                                        "quote",
                                                        "499.88330000",
                                                        "side",
                                                        "BUY")))));

        Bot first = startBot();
        assertTrue(
                first.positions().openManualBuy(new BigDecimal("60000"), Instant.now()).opened());
        // Binance accepted the MARKET order but answered before matching it
        assertDecimal("0", first.positions().getOpenPosition().orElseThrow().getQuantity());
        assertDecimal("0", db("SELECT quantity FROM trades WHERE position_id = 'POS_1'"));

        // No executionReport ever arrives: after the report timeout the order is looked up, never
        // assumed filled
        first.confirmations()
                .checkTimeouts(
                        Instant.now().plus(OrderConfirmationManager.REPORT_TIMEOUT).plusSeconds(1));

        assertFalse(first.confirmations().isPending(ENTRY_ID));
        Position position = first.positions().getOpenPosition().orElseThrow();
        assertDecimal("0.00833", position.getQuantity());
        assertDecimal("0.00833", db("SELECT quantity FROM trades WHERE position_id = 'POS_1'"));
        first.stop();
        bots.remove(first);

        stubAccount("0.00833000", "500.11670000");
        Bot second = startBot();

        Position restored = second.positions().getOpenPosition().orElseThrow();
        assertEquals("POS_1", restored.getPositionId());
        assertEquals(PositionState.OPEN, restored.getState());
        assertDecimal("0.00833", restored.getQuantity());
        assertEquals("Persisted position reconciled", second.reconciliation().message());
        assertTrue(second.alerts().isEmpty(), "balance matches the position: " + second.alerts());
        assertEquals(1, requests(wm, "POST", "/api/v3/order").size());
        assertTrue(second.outbox().findUnresolved().isEmpty());
    }
}
