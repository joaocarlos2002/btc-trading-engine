package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issues #70 and #111: order I/O runs outside the PositionManager lock, one order per pending state. */
public class OrderIoThreadingTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    private static final class Exchange extends BinanceOrderExecutor {
        final List<String> orders = new CopyOnWriteArrayList<>();
        volatile CountDownLatch sellGate = new CountDownLatch(0);
        final CountDownLatch sellStarted = new CountDownLatch(1);

        Exchange() {
            super("test-key", "test-secret");
        }

        @Override
        public OrderResult executeBuyMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            orders.add("buy");
            return new OrderResult(true, "1", new BigDecimal("0.5"), new BigDecimal("100"), null);
        }

        @Override
        public OrderResult executeSellMarket(String symbol, BigDecimal quantity, String clientOrderId) {
            orders.add("sell");
            sellStarted.countDown();
            try {
                sellGate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new OrderResult(true, "2", quantity, new BigDecimal("98"), null);
        }

        @Override
        public Optional<QueriedOrder> queryOrder(String symbol, String clientOrderId) {
            return Optional.empty();
        }

        @Override
        public SymbolFilters getSymbolFilters(String symbol) {
            return new SymbolFilters(symbol, new BigDecimal("10"), new BigDecimal("0.00001"),
                    new BigDecimal("1000"), new BigDecimal("0.00001"));
        }

        @Override
        public BalanceResult getBalance(String asset) {
            return new BalanceResult(true, new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"), null);
        }
    }

    /** Holds tasks until the test runs them, so every pending state can be observed. */
    private static final class QueuedExecutor implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.poll().run();
            }
        }
    }

    private final Exchange exchange = new Exchange();

    private PositionManager manager(Executor io) {
        PositionManager manager = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
        manager.setOrderIoExecutor(io);
        manager.setRealTradingMode(exchange, new PortfolioManager(new BigDecimal("1000"), new BigDecimal("50")), "BTCUSDT");
        manager.markReconciliationComplete();
        return manager;
    }

    private static void tick(PositionManager manager, String price) {
        manager.processPriceEvent(new NormalizedPriceEvent("BTCUSDT", new BigDecimal(price), NOW, NOW));
    }

    @Test
    public void pendingStatesNeverSendASecondOrder() {
        QueuedExecutor io = new QueuedExecutor();
        PositionManager manager = manager(io);

        PositionManager.ManualBuyResult result = manager.openManualBuy(new BigDecimal("100"), NOW);
        assertTrue(result.opened());
        assertEquals("Manual BUY entry order sent", result.message());
        assertEquals(PositionState.PENDING_ENTRY, manager.getOpenPosition().orElseThrow().getState());
        tick(manager, "90");
        assertEquals(1, io.tasks.size(), "a pending entry has nothing to exit");

        io.runAll();
        assertEquals(PositionState.OPEN, manager.getOpenPosition().orElseThrow().getState());

        tick(manager, "97");
        assertEquals(PositionState.EXIT_PENDING, manager.getOpenPosition().orElseThrow().getState());
        for (int i = 0; i < 50; i++) {
            tick(manager, "96");
        }
        manager.processPrediction(prediction(Signal.SELL), candle("96"));
        assertTrue(manager.closeManualPosition(new BigDecimal("96"), NOW), "the exit is already in flight");
        assertEquals(1, io.tasks.size());
        assertEquals(List.of("buy"), exchange.orders);

        io.runAll();
        assertEquals(List.of("buy", "sell"), exchange.orders);
        assertTrue(manager.getOpenPosition().isEmpty());
        assertEquals(PositionState.CLOSED, manager.getClosedPositions().getLast().getState());
        assertEquals(ExitReason.STOP_LOSS, manager.getClosedPositions().getLast().getExitReason());
    }

    @Test
    public void lockIsFreeWhileAnExitOrderIsInFlight() throws Exception {
        PositionManager manager = manager(Runnable::run);
        manager.openManualBuy(new BigDecimal("100"), NOW);
        manager.setOrderIoExecutor(command -> new Thread(command, "test-order-io").start());
        exchange.sellGate = new CountDownLatch(1);

        tick(manager, "97");
        assertTrue(exchange.sellStarted.await(5, TimeUnit.SECONDS));

        // Would block on the lock if the HTTP call held it
        CompletableFuture<PositionState> other = CompletableFuture.supplyAsync(() -> {
            tick(manager, "95");
            return manager.getOpenPosition().orElseThrow().getState();
        });
        assertEquals(PositionState.EXIT_PENDING, other.get(2, TimeUnit.SECONDS));

        exchange.sellGate.countDown();
        for (int i = 0; i < 100 && manager.getOpenPosition().isPresent(); i++) {
            Thread.sleep(20);
        }
        assertTrue(manager.getOpenPosition().isEmpty());
        assertEquals(List.of("buy", "sell"), exchange.orders);
    }

    @Test
    public void rejectedExitReturnsToOpen() {
        PositionManager manager = manager(Runnable::run);
        manager.openManualBuy(new BigDecimal("100"), NOW);
        manager.setOrderIoExecutor(command -> {
            throw new RejectedExecutionException("shut down");
        });

        assertFalse(manager.closeManualPosition(new BigDecimal("99"), NOW));

        assertEquals(PositionState.OPEN, manager.getOpenPosition().orElseThrow().getState());
        assertEquals(List.of("buy"), exchange.orders);
    }

    private static PredictionVector prediction(Signal signal) {
        return PredictionVector.builder()
                .instrument("BTCUSDT")
                .timestamp(NOW)
                .signal(signal)
                .probabilityUp(new BigDecimal("0.5"))
                .probabilityDown(new BigDecimal("0.5"))
                .confidence(new BigDecimal("0.5"))
                .price(new BigDecimal("100"))
                .modelVersion("test")
                .reason("test")
                .build();
    }

    private static CandleEvent candle(String close) {
        BigDecimal price = new BigDecimal(close);
        return new CandleEvent("BTCUSDT", NOW, NOW.plusSeconds(60), price, price, price, price, BigDecimal.ONE, 1);
    }
}
