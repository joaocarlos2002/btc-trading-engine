package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class PositionManager {
    private static final Logger logger = LoggerFactory.getLogger(PositionManager.class);

    private final BigDecimal targetPercent;
    private final BigDecimal stopLossPercent;
    private final AtomicInteger positionCounter = new AtomicInteger(0);
    private final Consumer<Position> positionPersistence;
    private final Consumer<ExecutionEvent> executionPersistence;

    private Optional<Position> openPosition = Optional.empty();
    private final List<Position> closedPositions = new ArrayList<>();
    private final List<ExecutionEvent> executionLog = new ArrayList<>();

    private Optional<BinanceOrderExecutor> orderExecutor = Optional.empty();
    private Optional<PortfolioManager> portfolioManager = Optional.empty();
    private Optional<BinanceSymbolValidationService> validationService = Optional.empty();
    private Optional<OrderConfirmationManager> confirmationManager = Optional.empty();
    private String symbol = "BTCUSDT";
    private boolean simulationMode = true;
    private volatile boolean reconciliationComplete = true;

    public PositionManager(BigDecimal targetPercent, BigDecimal stopLossPercent) {
        this(targetPercent, stopLossPercent, position -> {}, event -> {});
    }

    public PositionManager(BigDecimal targetPercent, BigDecimal stopLossPercent,
                           Consumer<Position> positionPersistence) {
        this(targetPercent, stopLossPercent, positionPersistence, event -> {});
    }

    public PositionManager(BigDecimal targetPercent, BigDecimal stopLossPercent,
                           Consumer<Position> positionPersistence,
                           Consumer<ExecutionEvent> executionPersistence) {
        this.targetPercent = targetPercent;
        this.stopLossPercent = stopLossPercent;
        this.positionPersistence = positionPersistence;
        this.executionPersistence = executionPersistence;
    }

    public void setRealTradingMode(BinanceOrderExecutor executor, PortfolioManager portfolio, String symbol) {
        this.orderExecutor = Optional.of(executor);
        this.portfolioManager = Optional.of(portfolio);
        this.validationService = Optional.of(new BinanceSymbolValidationService(executor));
        this.symbol = symbol;
        this.simulationMode = false;
        this.reconciliationComplete = false;
        logger.info("âœ“ Real trading mode ENABLED: {} with executor and portfolio manager", symbol);
    }

    public void setOrderConfirmationManager(OrderConfirmationManager manager) {
        this.confirmationManager = Optional.of(manager);
        manager.setConfirmationListener(this::onOrderConfirmed);
        logger.info("Order confirmation manager attached");
    }

    private void onOrderConfirmed(OrderConfirmationManager.OrderConfirmation confirmation) {
        if (openPosition.isEmpty()) {
            logger.warn("Received order confirmation but no open position: {}", confirmation.orderId());
            return;
        }

        Position pos = openPosition.get();
        logger.info("Order confirmed: {} status={} filledQty={} lastPrice={}",
                confirmation.orderId(), confirmation.status(), confirmation.filledQuantity(), confirmation.lastFillPrice());

        if (confirmation.isFilled()) {
            pos.applyFill(confirmation.filledQuantity());
            if (confirmation.lastFillPrice().compareTo(BigDecimal.ZERO) > 0) {
                pos.updatePrice(confirmation.lastFillPrice(), confirmation.confirmedAt());
            }
            positionPersistence.accept(pos);
        } else if (confirmation.isPartial()) {
            pos.applyFill(confirmation.filledQuantity());
            if (confirmation.lastFillPrice().compareTo(BigDecimal.ZERO) > 0) {
                pos.updatePrice(confirmation.lastFillPrice(), confirmation.confirmedAt());
            }
            logger.info("â³ Partial fill: {} filled={}/{} remaining={}",
                    confirmation.orderId(), pos.getQuantity(), pos.getTargetQuantity(), pos.getRemainingQuantity());
            positionPersistence.accept(pos);
        } else if (confirmation.isFailed()) {
            logger.error("âœ— Order {} failed: {}", confirmation.orderId(), confirmation.status());
            closePosition(pos, pos.getEntryPrice(), pos.getEntryTime(), "ORDER_FAILED");
        }
    }

    public synchronized void processPrediction(PredictionVector prediction, CandleEvent candle) {
        if (openPosition.isPresent()) {
            Position pos = openPosition.get();
            pos.updatePrice(candle.close(), candle.closeTime());

            if (pos.hasHitTarget()) {
                closePosition(pos, candle.close(), candle.closeTime(), "TARGET_HIT");
                logger.info("âœ“ TARGET HIT: {} closed at {} (P&L: {}%)",
                        pos.getPositionId(), candle.close(), pos.getPnLPercent());
                return;
            }

            if (pos.hasHitStopLoss()) {
                closePosition(pos, candle.close(), candle.closeTime(), "STOP_LOSS");
                logger.warn("âœ— STOP LOSS: {} closed at {} (P&L: {}%)",
                        pos.getPositionId(), candle.close(), pos.getPnLPercent());
                return;
            }

            if (shouldReversePosition(pos, prediction)) {
                closePosition(pos, candle.close(), candle.closeTime(), "SIGNAL_REVERSAL");
                logger.info("â†’ REVERSAL: {} closed at {} for new signal",
                        pos.getPositionId(), candle.close());
            }
        }

        if (!openPosition.isPresent() && prediction.signal() != Signal.HOLD) {
            if (!simulationMode && !reconciliationComplete) {
                logger.warn("Ignoring entry: Binance reconciliation is not complete");
                return;
            }
            if (portfolioManager.isPresent() && !portfolioManager.get().canTrade()) {
                logger.warn("âš  Portfolio stop-loss active - blocking new {} entry", prediction.signal());
                return;
            }
            openNewPosition(prediction, candle);
        }
    }

    public synchronized void processPriceEvent(NormalizedPriceEvent event) {
        if (openPosition.isEmpty() || event.price() == null) {
            return;
        }

        Position pos = openPosition.get();
        pos.updatePrice(event.price(), event.eventTimestamp());

        if (pos.hasHitTarget()) {
            closePosition(pos, event.price(), event.eventTimestamp(), "TARGET_HIT");
            logger.info("âœ“ TARGET HIT on tick: {} closed at {} (P&L: {}%)",
                    pos.getPositionId(), event.price(), pos.getPnLPercent());
        } else if (pos.hasHitStopLoss()) {
            closePosition(pos, event.price(), event.eventTimestamp(), "STOP_LOSS");
            logger.warn("âœ— STOP LOSS on tick: {} closed at {} (P&L: {}%)",
                    pos.getPositionId(), event.price(), pos.getPnLPercent());
        }
    }

    public synchronized boolean openManualBuy(BigDecimal price, java.time.Instant time) {
        if (openPosition.isPresent()) {
            return false;
        }
        if (portfolioManager.isPresent() && !portfolioManager.get().canTrade()) {
            return false;
        }

        openNewPosition(Signal.BUY, price, time, 0.0);
        return true;
    }

    public synchronized boolean restoreOpenPosition(Position position) {
        if (openPosition.isPresent() || position == null) {
            return false;
        }

        openPosition = Optional.of(position);
        String id = position.getPositionId();
        if (id.startsWith("POS_")) {
            try {
                positionCounter.updateAndGet(current -> Math.max(current, Integer.parseInt(id.substring(4))));
            } catch (NumberFormatException ignored) {
                logger.warn("Could not parse restored position id: {}", id);
            }
        }
        logger.info("Restored open simulated position: {}", position);
        return true;
    }

    public synchronized void restoreClosedPositions(List<Position> positions) {
        for (Position position : positions) {
            closedPositions.add(position);
            String id = position.getPositionId();
            if (id.startsWith("POS_")) {
                try {
                    positionCounter.updateAndGet(current -> Math.max(current, Integer.parseInt(id.substring(4))));
                } catch (NumberFormatException ignored) {
                    logger.warn("Could not parse restored position id: {}", id);
                }
            }
        }
    }

    public synchronized boolean closeManualPosition(BigDecimal price, java.time.Instant time) {
        if (openPosition.isEmpty() || price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        return closePosition(openPosition.get(), price, time, "MANUAL_CLOSE");
    }

    private boolean shouldReversePosition(Position pos, PredictionVector pred) {
        if (pos.getSignal() == Signal.BUY && pred.signal() == Signal.SELL) {
            return true;
        }
        if (pos.getSignal() == Signal.SELL && pred.signal() == Signal.BUY) {
            return true;
        }
        return false;
    }

    private void openNewPosition(PredictionVector prediction, CandleEvent candle) {
        openNewPosition(prediction.signal(), candle.close(), candle.closeTime(), prediction.confidence().doubleValue());
    }

    private void openNewPosition(Signal signal, BigDecimal price, java.time.Instant time, double confidence) {
        Position pos = new Position(
                String.format("POS_%d", positionCounter.incrementAndGet()),
                signal,
                price,
                time,
                targetPercent,
                stopLossPercent
        );
        openPosition = Optional.of(pos);

        ExecutionEvent event = new ExecutionEvent(
                pos.getPositionId(),
                "ENTRY",
            signal,
                price,
                time,
                confidence
        );
        executionLog.add(event);
        executionPersistence.accept(event);
        positionPersistence.accept(pos);

        logger.info("â†’ ENTRY: {} {} at {} (target: +{}%, stop: -{}%, confidence: {}%)",
                pos.getPositionId(),
                signal,
                price,
                targetPercent,
                stopLossPercent,
                java.math.BigDecimal.valueOf(confidence).multiply(new java.math.BigDecimal("100"))
                        .setScale(0, java.math.RoundingMode.HALF_UP));

        if (!simulationMode && orderExecutor.isPresent()) {
            executeRealOrder(pos, signal);
        }
    }

    private void executeRealOrder(Position pos, Signal signal) {
        try {
            logger.info("Executing real order for position: {}", pos.getPositionId());
            PortfolioManager pm = portfolioManager.get();
            BigDecimal allocatedCapital = pm.getCurrentBalance()
                    .multiply(new java.math.BigDecimal("0.5"))
                    .setScale(2, java.math.RoundingMode.DOWN);

            BigDecimal quantity = allocatedCapital
                    .divide(pos.getEntryPrice(), 4, java.math.RoundingMode.DOWN);

            if (validationService.isPresent()) {
                java.util.Optional<BigDecimal> validatedQty = validationService.get()
                        .validateAndAdjustQuantity(symbol, quantity, pos.getEntryPrice());
                if (validatedQty.isEmpty()) {
                    logger.error("âœ— Order rejected: quantity validation failed for {}", symbol);
                    closePosition(pos, pos.getEntryPrice(), pos.getEntryTime(), "VALIDATION_FAILED");
                    return;
                }
                quantity = validatedQty.get();
            }

            logger.info("Executing real {} order: qty={} {} @ {}", signal, quantity, symbol, pos.getEntryPrice());
            pos.setTargetQuantity(quantity);

            BinanceOrderExecutor executor = orderExecutor.get();
            BinanceOrderExecutor.OrderResult result;
            String clientOrderId = "btce-" + symbol + "-" + pos.getPositionId() + "-entry";

            if (signal == Signal.BUY) {
                result = executor.executeBuyMarket(symbol, quantity, clientOrderId);
            } else {
                result = executor.executeSellMarket(symbol, quantity, clientOrderId);
            }

            if (result.success()) {
                long orderId = Long.parseLong(result.orderId());

                if (confirmationManager.isPresent()) {
                    confirmationManager.get().registerOrder(orderId, symbol, signal.toString(), quantity, pos.getEntryPrice());
                }

                pos.applyFill(result.executedQuantity());
                if (result.averagePrice().compareTo(BigDecimal.ZERO) > 0) {
                    pos.setEntryPrice(result.averagePrice());
                    positionPersistence.accept(pos);
                }
                syncPortfolioBalance();
                logger.info("âœ“ Real order executed: orderId={} qty={} @ price={}",
                        result.orderId(), result.executedQuantity(), result.averagePrice());
                pos.updatePrice(result.averagePrice(), pos.getEntryTime());
            } else {
                logger.error("âœ— Real order failed: {}", result.error());
                closePosition(pos, pos.getEntryPrice(), pos.getEntryTime(), "ORDER_FAILED");
            }
        } catch (Exception e) {
            logger.error("âœ— Error executing real order: {}", e.getMessage(), e);
            closePosition(pos, pos.getEntryPrice(), pos.getEntryTime(), "ERROR");
        }
    }

    private boolean closePosition(Position pos, BigDecimal exitPrice, java.time.Instant exitTime, String reason) {
        if (!simulationMode && pos.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
            Optional<BinanceOrderExecutor.OrderResult> result = closeRealPosition(pos);
            if (result.isEmpty()) {
                logger.error("âœ— Position {} remains open locally because the real exit order failed", pos.getPositionId());
                return false;
            }
            if (result.get().averagePrice().compareTo(BigDecimal.ZERO) > 0) {
                exitPrice = result.get().averagePrice();
            }
        }

        switch (reason) {
            case "TARGET_HIT" -> pos.closeAtTarget(exitPrice, exitTime);
            case "STOP_LOSS" -> pos.closeAtStopLoss(exitPrice, exitTime);
            default -> pos.closeManual(exitPrice, exitTime);
        }
        closedPositions.add(pos);
        openPosition = Optional.empty();

        ExecutionEvent event = new ExecutionEvent(
                pos.getPositionId(),
                "EXIT",
                pos.getSignal(),
                exitPrice,
                exitTime,
                pos.getPnL().doubleValue()
        );
        executionLog.add(event);
        executionPersistence.accept(event);
        positionPersistence.accept(pos);
        return true;
    }

    private Optional<BinanceOrderExecutor.OrderResult> closeRealPosition(Position pos) {
        try {
            BinanceOrderExecutor executor = orderExecutor.get();
            BinanceOrderExecutor.OrderResult result = pos.getSignal() == Signal.BUY
                    ? executor.executeSellMarket(symbol, pos.getQuantity())
                    : executor.executeBuyMarket(symbol, pos.getQuantity());

            if (!result.success()) {
                logger.error("âœ— Real exit order failed for {}: {}", pos.getPositionId(), result.error());
                return Optional.empty();
            }

            logger.info("âœ“ Real exit order executed: orderId={} qty={} @ price={}",
                    result.orderId(), result.executedQuantity(), result.averagePrice());
                syncPortfolioBalance();
            return Optional.of(result);
        } catch (Exception e) {
            logger.error("âœ— Error executing real exit order for {}: {}", pos.getPositionId(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    private void syncPortfolioBalance() {
        if (portfolioManager.isEmpty() || orderExecutor.isEmpty()) {
            return;
        }

        BinanceOrderExecutor.BalanceResult balance = orderExecutor.get().getBalance("USDT");
        if (balance.success()) {
            portfolioManager.get().updateBalance(balance.total());
        } else {
            logger.warn("Could not refresh USDT balance after order: {}", balance.error());
        }
    }

    public synchronized Optional<Position> getOpenPosition() {
        return openPosition;
    }

    public void markReconciliationComplete() {
        reconciliationComplete = true;
        logger.info("Binance reconciliation complete; new entries enabled");
    }

    public synchronized List<Position> getClosedPositions() {
        return new ArrayList<>(closedPositions);
    }

    public synchronized List<ExecutionEvent> getExecutionLog() {
        return new ArrayList<>(executionLog);
    }

    public synchronized int getTotalTrades() {
        return closedPositions.size();
    }

    public synchronized int getWinTrades() {
        return (int) closedPositions.stream()
                .filter(p -> p.getPnL().compareTo(java.math.BigDecimal.ZERO) > 0)
                .count();
    }

    public synchronized BigDecimal getTotalPnL() {
        return closedPositions.stream()
                .map(Position::getPnL)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    public synchronized TestnetValidationReport getValidationReport() {
        return new TestnetValidationReport(getClosedPositions());
    }

    public record ExecutionEvent(
            String positionId,
            String action,
            Signal signal,
            java.math.BigDecimal price,
            java.time.Instant time,
            double value
    ) {}
}

