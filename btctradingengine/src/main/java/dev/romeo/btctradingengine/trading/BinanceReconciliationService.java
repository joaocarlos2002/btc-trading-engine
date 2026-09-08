package dev.romeo.btctradingengine.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.romeo.btctradingengine.prediction.Signal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class BinanceReconciliationService {
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

        if (openOrders.isEmpty()) {
            logger.info("âœ“ No open orders found on Binance for {}", symbol);
            return new ReconciliationResult(true, "No open orders", 0, null);
        }

        // Filter for the most recent pending order (there should be at most one active position)
        Optional<BinanceOrderExecutor.OpenOrder> activeOrder = openOrders.stream()
                .filter(o -> "NEW".equals(o.status()) || "PARTIALLY_FILLED".equals(o.status()))
                .findFirst();

        if (activeOrder.isPresent()) {
            BinanceOrderExecutor.OpenOrder order = activeOrder.get();
            logger.warn("âš  Found active order on Binance: {} {} {} qty @ {}",
                    order.orderId(), order.side(), order.origQuantity(), order.price());

            // Determine signal from side
            Signal signal = "BUY".equals(order.side()) ? Signal.BUY : Signal.SELL;

            // Create position from Binance state
            Position restoredPosition = new Position(
                    "BINANCE_" + order.orderId(),
                    signal,
                    order.price(),
                    Instant.ofEpochMilli(order.time()),
                    targetPercent,
                    stopLossPercent
            );
            restoredPosition.setQuantity(order.origQuantity());

            // Try to restore to PositionManager
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

        logger.warn("âš  Found {} open orders but none are active (all may be filled or cancelled)", openOrders.size());
        return new ReconciliationResult(true, "No active orders", openOrders.size(), null);
    }

    public record ReconciliationResult(
            boolean success,
            String message,
            int ordersFound,
            Position restoredPosition
    ) {}
}

