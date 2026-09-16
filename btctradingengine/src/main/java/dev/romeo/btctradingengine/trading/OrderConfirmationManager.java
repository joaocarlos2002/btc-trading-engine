package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.port.ExecutionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Tracks orders by clientOrderId until Binance confirms them. The order is registered before it is
 * sent, so an executionReport that arrives before the REST response still finds it (issue #63).
 */
public class OrderConfirmationManager {
    private static final Logger logger = LoggerFactory.getLogger(OrderConfirmationManager.class);

    // Without an executionReport after this long, the order is looked up on Binance
    static final Duration REPORT_TIMEOUT = Duration.ofSeconds(30);
    // If Binance still cannot be asked after this long, the order is reported as TIMEOUT
    static final Duration QUERY_GIVE_UP = Duration.ofMinutes(5);
    private static final long CHECK_INTERVAL_SECONDS = 15;

    private final Map<String, OrderTracker> pendingOrders = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "OrderConfirmationTimeout");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutionPort binanceExecutor;
    private volatile Consumer<OrderConfirmation> confirmationListener;

    public OrderConfirmationManager(ExecutionPort binanceExecutor) {
        this(binanceExecutor, true);
    }

    // Visible for testing: without the scheduler, the test drives checkTimeouts itself.
    OrderConfirmationManager(ExecutionPort binanceExecutor, boolean scheduleTimeoutChecks) {
        this.binanceExecutor = binanceExecutor;
        if (scheduleTimeoutChecks) {
            executor.scheduleAtFixedRate(this::runTimeoutCheck,
                    CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Must be called BEFORE the order is sent. */
    public void registerOrder(String clientOrderId, String symbol, String side, BigDecimal quantity, BigDecimal price) {
        pendingOrders.put(clientOrderId, new OrderTracker(clientOrderId, symbol, Instant.now(), false));
        logger.info("Registered order: clientOrderId={} {} {} qty={} @ {}", clientOrderId, side, symbol, quantity, price);
    }

    /**
     * A resting OCO leg (issue #99). It may wait hours for the price, so it is exempt from the
     * report timeout: the position manager polls it by REST instead.
     */
    public void registerProtectiveOrder(String clientOrderId, String symbol) {
        pendingOrders.put(clientOrderId, new OrderTracker(clientOrderId, symbol, Instant.now(), true));
        logger.info("Registered protective order: clientOrderId={} {}", clientOrderId, symbol);
    }

    /** For an order that was never accepted by Binance. */
    public void unregisterOrder(String clientOrderId) {
        pendingOrders.remove(clientOrderId);
    }

    public boolean isPending(String clientOrderId) {
        return pendingOrders.containsKey(clientOrderId);
    }

    public void processExecutionReport(BinanceUserDataStreamClient.ExecutionReport report) {
        OrderTracker tracker = pendingOrders.get(report.clientOrderId());
        if (tracker == null) {
            // Exit orders and already confirmed orders are not tracked
            logger.debug("Execution report for untracked order: orderId={} clientOrderId={}",
                    report.orderId(), report.clientOrderId());
            return;
        }

        BigDecimal cumulativeQty = new BigDecimal(report.cumulativeQty());
        BigDecimal lastPrice = new BigDecimal(report.lastPrice());
        logger.info("Execution Report: orderId={} clientOrderId={} status={} cumQty={} lastPrice={}",
                report.orderId(), report.clientOrderId(), report.orderStatus(), cumulativeQty, lastPrice);

        OrderStatus status = fromBinanceStatus(report.orderStatus(), cumulativeQty);
        if (status != null) {
            emit(tracker.apply(report.orderId(), cumulativeQty, lastPrice, status));
        }
    }

    /**
     * Orders without a report are looked up on Binance instead of being assumed FILLED: assuming it
     * confirmed a fill of 0 and left the bought BTC without an exit order (issue #63).
     */
    void checkTimeouts(Instant now) {
        for (OrderTracker tracker : pendingOrders.values()) {
            if (tracker.protective) {
                continue;
            }
            Duration age = Duration.between(tracker.createdAt, now);
            if (age.compareTo(REPORT_TIMEOUT) < 0) {
                continue;
            }

            Optional<BinanceOrderExecutor.QueriedOrder> queried =
                    binanceExecutor.queryOrder(tracker.symbol, tracker.clientOrderId);
            if (queried.isPresent()) {
                BinanceOrderExecutor.QueriedOrder order = queried.get();
                OrderStatus status = fromBinanceStatus(order.status(), order.executedQuantity());
                logger.warn("No execution report for {} after {}s; Binance reports status={} executedQty={}",
                        tracker.clientOrderId, age.toSeconds(), order.status(), order.executedQuantity());
                if (status != null) {
                    emit(tracker.apply(order.orderId(), order.executedQuantity(), order.averagePrice(), status));
                    if (status != OrderStatus.PARTIALLY_FILLED) {
                        continue;
                    }
                }
            }

            if (age.compareTo(QUERY_GIVE_UP) >= 0) {
                logger.error("Order {} still unconfirmed after {}s; giving up, known filled qty={}",
                        tracker.clientOrderId, age.toSeconds(), tracker.cumulativeQty);
                emit(tracker.expire());
            }
        }
    }

    private void runTimeoutCheck() {
        try {
            checkTimeouts(Instant.now());
        } catch (Exception e) {
            // An exception would cancel the scheduled task
            logger.error("Error checking order timeouts: {}", e.getMessage(), e);
        }
    }

    /** Null while the order is still working (NEW, PENDING_NEW...). */
    private static OrderStatus fromBinanceStatus(String status, BigDecimal executedQty) {
        return switch (status) {
            case "FILLED" -> OrderStatus.FILLED;
            case "PARTIALLY_FILLED" -> OrderStatus.PARTIALLY_FILLED;
            // A MARKET order short of liquidity ends EXPIRED with part executed: that part was bought
            case "CANCELED", "EXPIRED", "EXPIRED_IN_MATCH", "REJECTED" -> executedQty.signum() > 0
                    ? OrderStatus.FILLED
                    : "REJECTED".equals(status) ? OrderStatus.REJECTED : OrderStatus.CANCELED;
            default -> null;
        };
    }

    // Runs outside the tracker lock: the listener takes the PositionManager lock
    private void emit(Optional<OrderConfirmation> confirmation) {
        confirmation.ifPresent(conf -> {
            if (conf.status() != OrderStatus.PARTIALLY_FILLED) {
                pendingOrders.remove(conf.clientOrderId());
            }
            logger.info("Order {} ({}) confirmed: {} filledQty={} lastPrice={}",
                    conf.orderId(), conf.clientOrderId(), conf.status(), conf.filledQuantity(), conf.lastFillPrice());
            Consumer<OrderConfirmation> listener = confirmationListener;
            if (listener != null) {
                listener.accept(conf);
            }
        });
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
        final String clientOrderId;
        final String symbol;
        final Instant createdAt;
        final boolean protective;

        long orderId;
        BigDecimal cumulativeQty = BigDecimal.ZERO;
        BigDecimal lastFillPrice = BigDecimal.ZERO;
        boolean finished;

        OrderTracker(String clientOrderId, String symbol, Instant createdAt, boolean protective) {
            this.clientOrderId = clientOrderId;
            this.symbol = symbol;
            this.createdAt = createdAt;
            this.protective = protective;
        }

        synchronized Optional<OrderConfirmation> apply(long orderId, BigDecimal cumQty, BigDecimal price, OrderStatus status) {
            if (finished) {
                return Optional.empty();
            }
            if (orderId > 0) {
                this.orderId = orderId;
            }
            // Reports may arrive out of order: the cumulative quantity only grows
            if (cumQty.compareTo(cumulativeQty) > 0) {
                cumulativeQty = cumQty;
            }
            if (price.signum() > 0) {
                lastFillPrice = price;
            }
            finished = status != OrderStatus.PARTIALLY_FILLED;
            return Optional.of(confirmation(status));
        }

        synchronized Optional<OrderConfirmation> expire() {
            if (finished) {
                return Optional.empty();
            }
            finished = true;
            return Optional.of(confirmation(OrderStatus.TIMEOUT));
        }

        private OrderConfirmation confirmation(OrderStatus status) {
            return new OrderConfirmation(orderId, clientOrderId, symbol, status, cumulativeQty, lastFillPrice, Instant.now());
        }
    }

    public record OrderConfirmation(
            long orderId,
            String clientOrderId,
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

        public boolean isTimeout() {
            return status == OrderStatus.TIMEOUT;
        }
    }

    public enum OrderStatus {
        PARTIALLY_FILLED, FILLED, CANCELED, REJECTED, TIMEOUT
    }
}
