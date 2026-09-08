package dev.romeo.btctradingengine.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class OrderConfirmationManager {
    private static final Logger logger = LoggerFactory.getLogger(OrderConfirmationManager.class);

    private static final long ORDER_TIMEOUT_SECONDS = 30; // Timeout to assume filled if not confirmed
    private static final long CLEANUP_INTERVAL_SECONDS = 60; // Cleanup old orders every 60s

    private final Map<Long, OrderTracker> pendingOrders = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    private final BinanceOrderExecutor binanceExecutor;
    private Consumer<OrderConfirmation> confirmationListener;

    public OrderConfirmationManager(BinanceOrderExecutor binanceExecutor) {
        this.binanceExecutor = binanceExecutor;
        startTimeoutChecker();
    }

    public synchronized void registerOrder(long orderId, String symbol, String side, BigDecimal quantity, BigDecimal price) {
        OrderTracker tracker = new OrderTracker(orderId, symbol, side, quantity, price, Instant.now());
        pendingOrders.put(orderId, tracker);
        logger.info("Registered order: orderId={} {} {} qty={} @ {}", orderId, side, symbol, quantity, price);
    }

    public void processExecutionReport(BinanceUserDataStreamClient.ExecutionReport report) {
        Long orderId = report.orderId();
        OrderTracker tracker = pendingOrders.get(orderId);

        if (tracker == null) {
            logger.warn("âš  Received report for unknown order: {}", orderId);
            return;
        }

        BigDecimal cumulativeQty = new BigDecimal(report.cumulativeQty());
        BigDecimal lastPrice = new BigDecimal(report.lastPrice());

        logger.info("Execution Report: orderId={} status={} cumQty={} lastPrice={}",
                orderId, report.orderStatus(), cumulativeQty, lastPrice);

        tracker.updateWithFill(cumulativeQty, lastPrice, report.transactionTime());

        if (report.isFilled()) {
            confirmOrder(tracker, OrderStatus.FILLED);
        } else if (report.isPartiallyFilled()) {
            confirmOrder(tracker, OrderStatus.PARTIALLY_FILLED);
        } else if (report.isCanceled()) {
            confirmOrder(tracker, OrderStatus.CANCELED);
        } else if (report.isRejected()) {
            confirmOrder(tracker, OrderStatus.REJECTED);
        }
    }

    public Optional<OrderConfirmation> pollOrderStatus(String symbol, long orderId) {
        OrderTracker tracker = pendingOrders.get(orderId);
        if (tracker == null) {
            return Optional.empty();
        }

        // If we already got a websocket event, return cached
        if (tracker.confirmation != null) {
            return Optional.of(tracker.confirmation);
        }

        // Fallback: query Binance directly
        try {
            java.util.List<BinanceOrderExecutor.OpenOrder> openOrders = binanceExecutor.getOpenOrders(symbol);
            for (BinanceOrderExecutor.OpenOrder order : openOrders) {
                if (Long.parseLong(order.orderId()) == orderId) {
                    // Order still open
                    tracker.updateWithFill(order.executedQuantity(), order.price(), System.currentTimeMillis());
                    logger.debug("Polled order status: {} partially filled with qty={}", orderId, order.executedQuantity());
                    return Optional.empty(); // Still pending
                }
            }

            // If not in openOrders, assume filled (common for market orders)
            if (tracker.fillTime == null || tracker.fillTime.until(Instant.now(), ChronoUnit.SECONDS) > 5) {
                OrderConfirmation conf = new OrderConfirmation(
                        orderId,
                        tracker.symbol,
                        OrderStatus.FILLED,
                        tracker.cumulativeQty,
                        tracker.lastFillPrice,
                        Instant.ofEpochMilli(System.currentTimeMillis())
                );
                tracker.confirmation = conf;
                pendingOrders.remove(orderId);
                logger.info("âœ“ Order confirmed via polling: {} FILLED qty={}", orderId, tracker.cumulativeQty);
                return Optional.of(conf);
            }

        } catch (Exception e) {
            logger.warn("Error polling order status: {}", e.getMessage());
        }

        return Optional.empty();
    }

    private void confirmOrder(OrderTracker tracker, OrderStatus status) {
        if (tracker.confirmation != null) {
            return; // Already confirmed
        }

        OrderConfirmation confirmation = new OrderConfirmation(
                tracker.orderId,
                tracker.symbol,
                status,
                tracker.cumulativeQty,
                tracker.lastFillPrice,
                Instant.now()
        );
        tracker.confirmation = confirmation;

        if (status == OrderStatus.FILLED || status == OrderStatus.CANCELED || status == OrderStatus.REJECTED) {
            pendingOrders.remove(tracker.orderId);
        }

        if (confirmationListener != null) {
            confirmationListener.accept(confirmation);
        }

        logger.info("âœ“ Order {} confirmed: {} cumQty={} lastPrice={}",
                tracker.orderId, status, tracker.cumulativeQty, tracker.lastFillPrice);
    }

    private void startTimeoutChecker() {
        executor.scheduleAtFixedRate(() -> {
            Instant timeout = Instant.now().minus(ORDER_TIMEOUT_SECONDS, ChronoUnit.SECONDS);
            pendingOrders.forEach((orderId, tracker) -> {
                if (tracker.createdAt.isBefore(timeout) && tracker.confirmation == null) {
                    logger.warn("âš  Order {} timeout (no confirmation in {}s), assuming FILLED with qty={}",
                            orderId, ORDER_TIMEOUT_SECONDS, tracker.cumulativeQty);
                    confirmOrder(tracker, OrderStatus.FILLED);
                }
            });

            // Cleanup old confirmed orders
            pendingOrders.entrySet().removeIf(entry ->
                    entry.getValue().confirmation != null &&
                    entry.getValue().confirmation.confirmedAt().isBefore(Instant.now().minus(5, ChronoUnit.MINUTES))
            );

        }, CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void setConfirmationListener(Consumer<OrderConfirmation> listener) {
        this.confirmationListener = listener;
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static class OrderTracker {
        final long orderId;
        final String symbol;
        final String side;
        final BigDecimal requestedQuantity;
        final BigDecimal requestedPrice;
        final Instant createdAt;

        BigDecimal cumulativeQty = BigDecimal.ZERO;
        BigDecimal lastFillPrice = BigDecimal.ZERO;
        Instant fillTime;
        OrderConfirmation confirmation;

        OrderTracker(long orderId, String symbol, String side, BigDecimal quantity, BigDecimal price, Instant createdAt) {
            this.orderId = orderId;
            this.symbol = symbol;
            this.side = side;
            this.requestedQuantity = quantity;
            this.requestedPrice = price;
            this.createdAt = createdAt;
        }

        synchronized void updateWithFill(BigDecimal cumQty, BigDecimal lastPrice, long fillTimeMs) {
            this.cumulativeQty = cumQty;
            this.lastFillPrice = lastPrice;
            this.fillTime = Instant.ofEpochMilli(fillTimeMs);
        }
    }

    public record OrderConfirmation(
            long orderId,
            String symbol,
            OrderStatus status,
            BigDecimal filledQuantity,
            BigDecimal lastFillPrice,
            Instant confirmedAt
    ) {
        public boolean isFilled() {
            return status == OrderStatus.FILLED;
        }

        public boolean isPartial() {
            return status == OrderStatus.PARTIALLY_FILLED;
        }

        public boolean isFailed() {
            return status == OrderStatus.CANCELED || status == OrderStatus.REJECTED;
        }
    }

    public enum OrderStatus {
        PARTIALLY_FILLED, FILLED, CANCELED, REJECTED, TIMEOUT
    }
}

