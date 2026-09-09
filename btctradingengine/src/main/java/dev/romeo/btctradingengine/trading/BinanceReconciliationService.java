package dev.romeo.btctradingengine.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.romeo.btctradingengine.prediction.Signal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class BinanceReconciliationService {
    private static final String CLIENT_ORDER_PREFIX = "btce-";
    private static final Logger logger = LoggerFactory.getLogger(BinanceReconciliationService.class);

    private final BinanceOrderExecutor orderExecutor;
    private final PositionManager positionManager;
    private final BigDecimal targetPercent;
    private final BigDecimal stopLossPercent;

    public BinanceReconciliationService(
            BinanceOrderExecutor orderExecutor,
            PositionManager positionManager,
            BigDecimal targetPercent,
            BigDecimal stopLossPercent) {
        this.orderExecutor = orderExecutor;
        this.positionManager = positionManager;
        this.targetPercent = targetPercent;
        this.stopLossPercent = stopLossPercent;
    }

    public ReconciliationResult reconcile(String symbol) {
        logger.info("Starting Binance reconciliation for {}", symbol);

        List<BinanceOrderExecutor.OpenOrder> openOrders = orderExecutor.getOpenOrders(symbol);

        List<BinanceOrderExecutor.OpenOrder> botOrders = openOrders.stream()
            .filter(order -> order.clientOrderId() != null
                && order.clientOrderId().startsWith(CLIENT_ORDER_PREFIX))
            .toList();

        if (openOrders.size() > botOrders.size()) {
            logger.warn("Found {} Binance order(s) without the bot prefix; leaving them untouched",
                openOrders.size() - botOrders.size());
        }

        if (botOrders.isEmpty()) {
            logger.info("âœ“ No open orders found on Binance for {}", symbol);
            return new ReconciliationResult(true, "No open orders", 0, null);
        }

        Optional<BinanceOrderExecutor.OpenOrder> activeOrder = botOrders.stream()
                .filter(o -> "NEW".equals(o.status()) || "PARTIALLY_FILLED".equals(o.status()))
                .findFirst();

        if (activeOrder.isPresent()) {
            BinanceOrderExecutor.OpenOrder order = activeOrder.get();
            logger.warn("âš  Found active order on Binance: {} {} {} qty @ {}",
                    order.orderId(), order.side(), order.origQuantity(), order.price());

            botOrders.stream()
                    .filter(candidate -> !candidate.orderId().equals(order.orderId()))
                    .forEach(candidate -> {
                        if (orderExecutor.cancelOrder(symbol, candidate.orderId())) {
                            logger.warn("Cancelled orphan bot order {} ({})",
                                    candidate.orderId(), candidate.clientOrderId());
                        }
                    });

            Signal signal = "BUY".equals(order.side()) ? Signal.BUY : Signal.SELL;

            Position restoredPosition = new Position(
                    "BINANCE_" + order.orderId(),
                    signal,
                    order.price(),
                    Instant.ofEpochMilli(order.time()),
                    targetPercent,
                    stopLossPercent
            );
            restoredPosition.setQuantity(order.origQuantity());

            if (positionManager.getOpenPosition().isPresent()) {
                Position localPosition = positionManager.getOpenPosition().get();
                if (order.executedQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    localPosition.setQuantity(order.executedQuantity());
                }
                logger.warn("Kept persisted local position while reconciling Binance order {}",
                        order.orderId());
                return new ReconciliationResult(true, "Existing position reconciled", botOrders.size(), localPosition);
            }

            boolean restored = positionManager.restoreOpenPosition(restoredPosition);
            if (restored) {
                logger.warn("âœ“ Restored open position from Binance: {}", restoredPosition);
                return new ReconciliationResult(true, "Position restored from Binance", 1, restoredPosition);
            } else {
                logger.error("âœ— Could not restore position - local position already exists? (Binance has {})",
                        order.orderId());
                return new ReconciliationResult(false, "Local position conflict", 1, null);
            }
        }

        if (positionManager.getOpenPosition().isPresent()) {
            Position localPosition = positionManager.getOpenPosition().get();
            String clientOrderId = CLIENT_ORDER_PREFIX + symbol + "-"
                    + localPosition.getPositionId() + "-entry";
            orderExecutor.findOrderByClientOrderId(symbol, clientOrderId).ifPresent(result -> {
                if (result.executedQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    localPosition.setQuantity(result.executedQuantity());
                    if (result.averagePrice().compareTo(BigDecimal.ZERO) > 0) {
                        localPosition.updatePrice(result.averagePrice(), Instant.now());
                    }
                    logger.warn("Recovered filled entry {} after restart: qty={} price={}",
                            clientOrderId, result.executedQuantity(), result.averagePrice());
                }
            });
            return new ReconciliationResult(true, "Persisted position reconciled", botOrders.size(), localPosition);
        }

        logger.warn("âš  Found {} bot order(s) but none are active", botOrders.size());
        return new ReconciliationResult(true, "No active bot orders", botOrders.size(), null);
    }

    public record ReconciliationResult(
            boolean success,
            String message,
            int ordersFound,
            Position restoredPosition
    ) {}
}

