package dev.romeo.btctradingengine.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.romeo.btctradingengine.alerting.AlertNotifier;
import dev.romeo.btctradingengine.prediction.Signal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class BinanceReconciliationService {
    private static final String CLIENT_ORDER_PREFIX = "btce-";
    private static final String QUOTE_ASSET = "USDT";
    // A BUY pays its commission in the bought asset unless BNB is used, so the balance can be ~0.1% short
    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.99");
    private static final Logger logger = LoggerFactory.getLogger(BinanceReconciliationService.class);

    private final BinanceOrderExecutor orderExecutor;
    private final PositionManager positionManager;
    private final BigDecimal targetPercent;
    private final BigDecimal stopLossPercent;
    private final AlertNotifier alertNotifier;

    public BinanceReconciliationService(
            BinanceOrderExecutor orderExecutor,
            PositionManager positionManager,
            BigDecimal targetPercent,
            BigDecimal stopLossPercent) {
        this(orderExecutor, positionManager, targetPercent, stopLossPercent, null);
    }

    public BinanceReconciliationService(
            BinanceOrderExecutor orderExecutor,
            PositionManager positionManager,
            BigDecimal targetPercent,
            BigDecimal stopLossPercent,
            AlertNotifier alertNotifier) {
        this.orderExecutor = orderExecutor;
        this.positionManager = positionManager;
        this.targetPercent = targetPercent;
        this.stopLossPercent = stopLossPercent;
        this.alertNotifier = alertNotifier;
    }

    public ReconciliationResult reconcile(String symbol) {
        logger.info("Starting Binance reconciliation for {}", symbol);

        List<BinanceOrderExecutor.OpenOrder> openOrders = orderExecutor.getOpenOrders(symbol);

        List<BinanceOrderExecutor.OpenOrder> allBotOrders = openOrders.stream()
            .filter(order -> order.clientOrderId() != null
                && order.clientOrderId().startsWith(CLIENT_ORDER_PREFIX))
            .toList();

        if (openOrders.size() > allBotOrders.size()) {
            logger.warn("Found {} Binance order(s) without the bot prefix; leaving them untouched",
                openOrders.size() - allBotOrders.size());
        }

        // OCO legs (issue #99) protect a position; they are not entries to restore
        List<BinanceOrderExecutor.OpenOrder> botOrders = allBotOrders.stream()
            .filter(order -> !OcoOrderIds.isLeg(order.clientOrderId()))
            .toList();
        cancelOrphanProtection(symbol, allBotOrders);

        if (botOrders.isEmpty()) {
            logger.info("âœ“ No open orders found on Binance for {}", symbol);
            // MARKET orders never stay open, so this is the usual path after a restart with a
            // position: it must still recover the fill (issue #64)
            if (positionManager.getOpenPosition().isPresent()) {
                return reconcilePersistedPosition(symbol, positionManager.getOpenPosition().get(), 0);
            }
            return new ReconciliationResult(true, "No open orders", allBotOrders.size(), null);
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
                logger.info("OCO protection of {}: {}", localPosition.getPositionId(),
                        positionManager.reconcileProtection());
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
            return reconcilePersistedPosition(symbol, positionManager.getOpenPosition().get(), botOrders.size());
        }

        logger.warn("âš  Found {} bot order(s) but none are active", botOrders.size());
        return new ReconciliationResult(true, "No active bot orders", botOrders.size(), null);
    }

    /**
     * Binance is the authority on how much the persisted entry filled. When it cannot be asked, the
     * quantity persisted in the journal is kept.
     */
    private ReconciliationResult reconcilePersistedPosition(String symbol, Position localPosition, int ordersFound) {
        String clientOrderId = CLIENT_ORDER_PREFIX + symbol + "-" + localPosition.getPositionId() + "-entry";
        orderExecutor.findOrderByClientOrderId(symbol, clientOrderId).ifPresentOrElse(result -> {
            if (result.executedQuantity().compareTo(BigDecimal.ZERO) > 0) {
                localPosition.setQuantity(result.executedQuantity());
                if (result.averagePrice().compareTo(BigDecimal.ZERO) > 0) {
                    localPosition.updatePrice(result.averagePrice(), Instant.now());
                }
                logger.warn("Recovered filled entry {} after restart: qty={} price={}",
                        clientOrderId, result.executedQuantity(), result.averagePrice());
            }
        }, () -> logger.warn("Entry {} not found on Binance; keeping persisted qty={}",
                clientOrderId, localPosition.getQuantity()));

        checkBaseAssetBalance(symbol, localPosition);
        PositionManager.ProtectionReconciliation protection = positionManager.reconcileProtection();
        logger.info("OCO protection of {} after reconciliation: {}", localPosition.getPositionId(), protection);
        if (protection == PositionManager.ProtectionReconciliation.CLOSED_BY_EXCHANGE) {
            return new ReconciliationResult(true, "Persisted position closed by its OCO", ordersFound, null);
        }
        if (protection == PositionManager.ProtectionReconciliation.PLACEMENT_FAILED) {
            alert(String.format("OCO protection could not be re-placed for position %s after restart",
                    localPosition.getPositionId()));
        }
        return new ReconciliationResult(true, "Persisted position reconciled", ordersFound, localPosition);
    }

    /**
     * An OCO whose position is not open locally would sell the account's BTC later for a trade the
     * bot no longer tracks: cancel it.
     */
    private void cancelOrphanProtection(String symbol, List<BinanceOrderExecutor.OpenOrder> botOrders) {
        Optional<String> ownList = positionManager.getOpenPosition()
                .map(position -> OcoOrderIds.list(symbol, position.getPositionId()));
        botOrders.stream()
                .map(order -> OcoOrderIds.listOfLeg(order.clientOrderId()))
                .flatMap(Optional::stream)
                .distinct()
                .filter(listId -> ownList.map(own -> !own.equals(listId)).orElse(true))
                .forEach(listId -> {
                    var cancel = orderExecutor.cancelOrderList(symbol, listId);
                    alert(String.format("Orphan OCO %s without an open position: cancel %s", listId, cancel.state()));
                });
    }

    /** A BUY position the account no longer holds cannot be exited: say so before trading starts. */
    private void checkBaseAssetBalance(String symbol, Position position) {
        if (position.getSignal() != Signal.BUY || position.getQuantity().signum() <= 0
                || !symbol.endsWith(QUOTE_ASSET)) {
            return;
        }

        String baseAsset = symbol.substring(0, symbol.length() - QUOTE_ASSET.length());
        BinanceOrderExecutor.BalanceResult balance = orderExecutor.getBalance(baseAsset);
        BigDecimal minimum = position.getQuantity().multiply(BALANCE_TOLERANCE);
        if (!balance.success()) {
            alert(String.format("Could not check %s balance for position %s (qty=%s): %s",
                    baseAsset, position.getPositionId(), position.getQuantity(), balance.error()));
        } else if (balance.total().compareTo(minimum) < 0) {
            alert(String.format("%s balance %s is below position %s qty=%s: the exit order may fail, check it manually",
                    baseAsset, balance.total(), position.getPositionId(), position.getQuantity()));
        }
    }

    private void alert(String message) {
        logger.error(message);
        if (alertNotifier != null) {
            alertNotifier.alert(message);
        }
    }

    public record ReconciliationResult(
            boolean success,
            String message,
            int ordersFound,
            Position restoredPosition
    ) {}
}

