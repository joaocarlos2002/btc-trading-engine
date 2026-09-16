package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.alerting.AlertNotifier;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
    private Optional<ConnectivityGuard> connectivityGuard = Optional.empty();
    private Optional<AlertNotifier> alertNotifier = Optional.empty();
    private String symbol = "BTCUSDT";
    private boolean simulationMode = true;
    private boolean allowShort = false;
    private volatile boolean reconciliationComplete = true;

    // Exit retry backoff (issue #68): a failed exit must not resend an order on every tick
    static final Duration EXIT_RETRY_INITIAL_DELAY = Duration.ofSeconds(1);
    static final Duration EXIT_RETRY_MAX_DELAY = Duration.ofSeconds(60);
    static final int EXIT_FAILURE_ALERT_EVERY = 10;
    private Supplier<Instant> clock = Instant::now;
    private int exitFailureCount = 0;
    private Instant nextExitAttemptAt = null;
    private BigDecimal cachedStepSize = null;

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
        if (allowShort) {
            logger.warn("trading.allow.short is on, but the spot market has no short selling: "
                    + "SELL signals will only close an open BUY, never open a position");
        }
    }

    /**
     * Whether a SELL signal may OPEN a position. Simulation only: real spot trading never opens a
     * short (issue #67), so this exists to keep simulated results comparable with what is executable.
     */
    public void setAllowShort(boolean allowShort) {
        this.allowShort = allowShort;
    }

    /**
     * The spot market has no short selling. With no open position, a SELL would send a SELL MARKET
     * order for BTC the bot never bought: rejected for insufficient balance, or - if the account
     * happens to hold BTC - selling the user's own coins to buy them back later (issue #67).
     *
     * <p>Only blocks OPENING a position: a SELL still closes an open BUY through the reversal above.
     */
    private boolean shortEntryAllowed() {
        return simulationMode && allowShort;
    }

    public void setOrderConfirmationManager(OrderConfirmationManager manager) {
        this.confirmationManager = Optional.of(manager);
        manager.setConfirmationListener(this::onOrderConfirmed);
        logger.info("Order confirmation manager attached");
    }

    public void setConnectivityGuard(ConnectivityGuard guard) {
        this.connectivityGuard = Optional.of(guard);
        guard.setOpenPositionSupplier(() -> openPosition.isPresent());
        logger.info("Connectivity guard attached");
    }

    public void setAlertNotifier(AlertNotifier notifier) {
        this.alertNotifier = Optional.of(notifier);
    }

    /** Time source for the exit retry backoff; tests advance it instead of sleeping. */
    synchronized void setClock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    // synchronized: confirmations come from the User Data Stream and timeout threads
    private synchronized void onOrderConfirmed(OrderConfirmationManager.OrderConfirmation confirmation) {
        if (openPosition.isEmpty()
                || !entryClientOrderId(openPosition.get()).equals(confirmation.clientOrderId())) {
            logger.warn("Received order confirmation that does not match the open position: {} ({})",
                    confirmation.orderId(), confirmation.clientOrderId());
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
            alertNotifier.ifPresent(a -> a.alert(String.format(
                    "Order %s for position %s failed: %s", confirmation.orderId(), pos.getPositionId(), confirmation.status())));
            closePosition(pos, pos.getEntryPrice(), pos.getEntryTime(), ExitReason.ORDER_FAILED);
        } else if (confirmation.isTimeout()) {
            // Never guess a fill: keep the quantity already known and ask for a manual check
            logger.error("Order {} for position {} could not be confirmed; keeping qty={}",
                    confirmation.clientOrderId(), pos.getPositionId(), pos.getQuantity());
            alertNotifier.ifPresent(a -> a.alert(String.format(
                    "Order %s for position %s could not be confirmed on Binance; position kept with qty=%s, check it manually",
                    confirmation.clientOrderId(), pos.getPositionId(), pos.getQuantity())));
        }
    }

    private String entryClientOrderId(Position pos) {
        return "btce-" + symbol + "-" + pos.getPositionId() + "-entry";
    }

    public synchronized void processPrediction(PredictionVector prediction, CandleEvent candle) {
        if (openPosition.isPresent()) {
            Position pos = openPosition.get();
            pos.updatePrice(candle.close(), candle.closeTime());

            if (pos.hasHitTarget()) {
                closePosition(pos, candle.close(), candle.closeTime(), ExitReason.TARGET_HIT);
                logger.info("âœ“ TARGET HIT: {} closed at {} (P&L: {}%)",
                        pos.getPositionId(), candle.close(), pos.getPnLPercent());
                return;
            }

            if (pos.hasHitStopLoss()) {
                closePosition(pos, candle.close(), candle.closeTime(), ExitReason.STOP_LOSS);
                logger.warn("âœ— STOP LOSS: {} closed at {} (P&L: {}%)",
                        pos.getPositionId(), candle.close(), pos.getPnLPercent());
                return;
            }

            if (shouldReversePosition(pos, prediction)) {
                closePosition(pos, candle.close(), candle.closeTime(), ExitReason.SIGNAL_REVERSAL);
                logger.info("â†’ REVERSAL: {} closed at {} for new signal",
                        pos.getPositionId(), candle.close());
            }
        }

        if (!openPosition.isPresent() && prediction.signal() != Signal.HOLD) {
            if (prediction.signal() == Signal.SELL && !shortEntryAllowed()) {
                logger.info(simulationMode
                        ? "SELL signal does not open a short (trading.allow.short=false)"
                        : "SELL signal does not open a position: the spot market has no short selling");
                return;
            }
            Optional<String> blocked = entryBlockReason();
            if (blocked.isPresent()) {
                logger.warn("Blocking new {} entry: {}", prediction.signal(), blocked.get());
                return;
            }
            if (!prediction.entryAllowed()) {
                // Only new entries: the reversal close above has already run for this prediction
                logger.info("Entry blocked (filter rules / VPIN / order book) - not opening new {} position",
                        prediction.signal());
                return;
            }
            openNewPosition(prediction, candle);
        }
    }

    public synchronized void processPriceEvent(NormalizedPriceEvent event) {
        if (event.price() != null) {
            // Keeps equity drawdown current between orders; in-memory only, no HTTP
            portfolioManager.ifPresent(pm -> pm.updateMarketPrice(event.price()));
        }
        if (openPosition.isEmpty() || event.price() == null) {
            return;
        }

        Position pos = openPosition.get();
        pos.updatePrice(event.price(), event.eventTimestamp());

        if (pos.hasHitTarget()) {
            if (!closePosition(pos, event.price(), event.eventTimestamp(), ExitReason.TARGET_HIT)) {
                return;
            }
            logger.info("âœ“ TARGET HIT on tick: {} closed at {} (P&L: {}%)",
                    pos.getPositionId(), event.price(), pos.getPnLPercent());
        } else if (pos.hasHitStopLoss()) {
            if (!closePosition(pos, event.price(), event.eventTimestamp(), ExitReason.STOP_LOSS)) {
                return;
            }
            logger.warn("âœ— STOP LOSS on tick: {} closed at {} (P&L: {}%)",
                    pos.getPositionId(), event.price(), pos.getPnLPercent());
        }
    }

    /**
     * Guards shared by automatic and manual entries (issue #79): the dashboard starts before the
     * Binance reconciliation, so a manual BUY must not bypass them.
     */
    private Optional<String> entryBlockReason() {
        if (!simulationMode && !reconciliationComplete) {
            return Optional.of("Binance reconciliation is not complete");
        }
        if (portfolioManager.isPresent() && !portfolioManager.get().canTrade()) {
            return Optional.of("Portfolio stop-loss is active (max drawdown reached)");
        }
        if (connectivityGuard.isPresent() && !connectivityGuard.get().isHealthy()) {
            return Optional.of("Connectivity guard is unhealthy: " + connectivityGuard.get().getUnhealthyReason());
        }
        return Optional.empty();
    }

    public synchronized ManualBuyResult openManualBuy(BigDecimal price, java.time.Instant time) {
        if (openPosition.isPresent()) {
            return ManualBuyResult.blocked("A position is already open");
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return ManualBuyResult.blocked("No valid market price");
        }
        Optional<String> blocked = entryBlockReason();
        if (blocked.isPresent()) {
            logger.warn("Blocking manual BUY: {}", blocked.get());
            return ManualBuyResult.blocked(blocked.get());
        }

        openNewPosition(Signal.BUY, price, time, 0.0);
        if (openPosition.isEmpty()) {
            // The real entry order failed and the position was closed with its failure reason
            ExitReason reason = closedPositions.isEmpty() ? null : closedPositions.getLast().getExitReason();
            return ManualBuyResult.blocked("Entry order was not executed (" + reason + ")");
        }
        return new ManualBuyResult(true, "Manual BUY opened");
    }

    public record ManualBuyResult(boolean opened, String message) {
        static ManualBuyResult blocked(String message) {
            return new ManualBuyResult(false, message);
        }
    }

    public synchronized boolean restoreOpenPosition(Position position) {
        if (openPosition.isPresent() || position == null) {
            return false;
        }

        openPosition = Optional.of(position);
        resetExitBackoff();
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

        // An explicit user request skips the backoff wait; failures still count
        return closePosition(openPosition.get(), price, time, ExitReason.MANUAL_CLOSE, false);
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
        resetExitBackoff();

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
                    .divide(pos.getEntryPrice(), 8, java.math.RoundingMode.DOWN);

            if (validationService.isPresent()) {
                java.util.Optional<BigDecimal> validatedQty = validationService.get()
                        .validateAndAdjustQuantity(symbol, quantity, pos.getEntryPrice(), allocatedCapital);
                if (validatedQty.isEmpty()) {
                    logger.error("âœ— Order rejected: quantity validation failed for {}", symbol);
                    alertNotifier.ifPresent(a -> a.alert(String.format(
                            "Order rejected for position %s: quantity validation failed for %s", pos.getPositionId(), symbol)));
                    closePosition(pos, pos.getEntryPrice(), pos.getEntryTime(), ExitReason.VALIDATION_FAILED);
                    return;
                }
                quantity = validatedQty.get();
            }

            logger.info("Executing real {} order: qty={} {} @ {}", signal, quantity, symbol, pos.getEntryPrice());
            pos.setTargetQuantity(quantity);

            BinanceOrderExecutor executor = orderExecutor.get();
            BinanceOrderExecutor.OrderResult result;
            String clientOrderId = entryClientOrderId(pos);
            // Before sending: a MARKET order fills at once and its report can beat the REST response
            if (confirmationManager.isPresent()) {
                confirmationManager.get().registerOrder(clientOrderId, symbol, signal.toString(), quantity, pos.getEntryPrice());
            }

            if (signal == Signal.BUY) {
                result = executor.executeBuyMarket(symbol, quantity, clientOrderId);
            } else {
                result = executor.executeSellMarket(symbol, quantity, clientOrderId);
            }

            if (result.success()) {
                pos.applyFill(result.executedQuantity());
                if (result.averagePrice().compareTo(BigDecimal.ZERO) > 0) {
                    pos.setEntryPrice(result.averagePrice());
                }
                // Always: the quantity is what a restart needs to send the exit order (issue #64)
                positionPersistence.accept(pos);
                syncPortfolioBalance(pos.getEntryPrice());
                logger.info("âœ“ Real order executed: orderId={} qty={} @ price={}",
                        result.orderId(), result.executedQuantity(), result.averagePrice());
                if (result.averagePrice().signum() > 0) pos.updatePrice(result.averagePrice(), pos.getEntryTime());
            } else {
                confirmationManager.ifPresent(m -> m.unregisterOrder(clientOrderId));
                logger.error("âœ— Real order failed: {}", result.error());
                alertNotifier.ifPresent(a -> a.alert(String.format(
                        "Real order failed for position %s: %s", pos.getPositionId(), result.error())));
                closePosition(pos, pos.getEntryPrice(), pos.getEntryTime(), ExitReason.ORDER_FAILED);
            }
        } catch (Exception e) {
            logger.error("âœ— Error executing real order: {}", e.getMessage(), e);
            alertNotifier.ifPresent(a -> a.alert(String.format(
                    "Exception executing real order for position %s: %s", pos.getPositionId(), e.getMessage())));
            closePosition(pos, pos.getEntryPrice(), pos.getEntryTime(), ExitReason.ERROR);
        }
    }

    private boolean closePosition(Position pos, BigDecimal exitPrice, java.time.Instant exitTime, ExitReason reason) {
        return closePosition(pos, exitPrice, exitTime, reason, true);
    }

    private boolean closePosition(Position pos, BigDecimal exitPrice, java.time.Instant exitTime, ExitReason reason,
                                  boolean respectBackoff) {
        if (!simulationMode && pos.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
            Instant now = clock.get();
            if (respectBackoff && nextExitAttemptAt != null && now.isBefore(nextExitAttemptAt)) {
                return false;
            }
            Optional<BinanceOrderExecutor.OrderResult> result = closeRealPosition(pos);
            if (result.isEmpty()) {
                exitFailureCount++;
                Duration delay = exitRetryDelay(exitFailureCount);
                nextExitAttemptAt = now.plus(delay);
                logger.error("âœ— Position {} remains open locally because the real exit order failed", pos.getPositionId());
                logger.error("Exit attempt {} failed for {}; next attempt in {}s",
                        exitFailureCount, pos.getPositionId(), delay.toSeconds());
                // Alert on the first failure and every 10th, not on every retry
                if (exitFailureCount == 1 || exitFailureCount % EXIT_FAILURE_ALERT_EVERY == 0) {
                    int failures = exitFailureCount;
                    alertNotifier.ifPresent(a -> a.alert(String.format(
                            "CRITICAL: exit order failed for position %s (reason: %s, %d failed attempts) - position remains OPEN and unmanaged, manual intervention required",
                            pos.getPositionId(), reason, failures)));
                }
                return false;
            }
            if (result.get().averagePrice().compareTo(BigDecimal.ZERO) > 0) {
                exitPrice = result.get().averagePrice();
            }
        }

        resetExitBackoff();
        pos.close(exitPrice, exitTime, reason);
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

    /** 1s, 2s, 4s ... capped at 60s. */
    static Duration exitRetryDelay(int failures) {
        int shift = Math.min(Math.max(failures - 1, 0), 16);
        Duration delay = EXIT_RETRY_INITIAL_DELAY.multipliedBy(1L << shift);
        return delay.compareTo(EXIT_RETRY_MAX_DELAY) > 0 ? EXIT_RETRY_MAX_DELAY : delay;
    }

    private void resetExitBackoff() {
        exitFailureCount = 0;
        nextExitAttemptAt = null;
    }

    private String exitClientOrderId(Position pos) {
        return "btce-" + symbol + "-" + pos.getPositionId() + "-exit";
    }

    /**
     * Quantity to sell when closing a BUY (issue #69): without BNB fees Binance takes the buy
     * commission from the base asset, so the free balance can be below the filled quantity.
     * Uses min(position qty, free base balance), rounded down to the LOT_SIZE step.
     */
    BigDecimal sellableExitQuantity(Position pos) {
        BinanceOrderExecutor executor = orderExecutor.get();
        BigDecimal quantity = pos.getQuantity();

        String baseAsset = baseAsset();
        BinanceOrderExecutor.BalanceResult balance = executor.getBalance(baseAsset);
        if (balance != null && balance.success()) {
            if (balance.free().compareTo(quantity) < 0) {
                logger.warn("Free {} balance {} is below position {} qty {}; selling the free balance",
                        baseAsset, balance.free(), pos.getPositionId(), quantity);
                quantity = balance.free();
            }
        } else {
            logger.warn("Could not fetch free {} balance before exit ({}); using position qty {}",
                    baseAsset, balance == null ? "no response" : balance.error(), quantity);
        }

        BigDecimal stepSize = exitStepSize();
        if (stepSize == null) {
            logger.warn("Could not fetch LOT_SIZE step for {}; exit qty {} not rounded", symbol, quantity);
            return quantity;
        }
        return quantity.divide(stepSize, 0, java.math.RoundingMode.DOWN).multiply(stepSize).stripTrailingZeros();
    }

    // Cached on success: the step rarely changes and exits run inside this lock
    private BigDecimal exitStepSize() {
        if (cachedStepSize == null) {
            BinanceOrderExecutor.SymbolFilters filters = orderExecutor.get().getSymbolFilters(symbol);
            if (filters != null && filters.stepSize() != null && filters.stepSize().compareTo(BigDecimal.ZERO) > 0) {
                cachedStepSize = filters.stepSize();
            }
        }
        return cachedStepSize;
    }

    private Optional<BinanceOrderExecutor.OrderResult> closeRealPosition(Position pos) {
        try {
            BinanceOrderExecutor executor = orderExecutor.get();
            String clientOrderId = exitClientOrderId(pos);
            BinanceOrderExecutor.OrderResult result;
            if (pos.getSignal() == Signal.BUY) {
                BigDecimal quantity = sellableExitQuantity(pos);
                if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                    logger.error("No sellable quantity for {} (position qty={})", pos.getPositionId(), pos.getQuantity());
                    return Optional.empty();
                }
                result = executor.executeSellMarket(symbol, quantity, clientOrderId);
            } else {
                result = executor.executeBuyMarket(symbol, pos.getQuantity(), clientOrderId);
            }

            if (!result.success()) {
                // The error may be ambiguous (e.g. a timeout after Binance accepted the order)
                Optional<BinanceOrderExecutor.QueriedOrder> sent = executor.queryOrder(symbol, clientOrderId);
                if (sent.isPresent() && "FILLED".equals(sent.get().status())) {
                    BinanceOrderExecutor.QueriedOrder order = sent.get();
                    logger.warn("Exit order {} reported an error but is FILLED on Binance: {}", clientOrderId, result.error());
                    syncPortfolioBalance(order.averagePrice());
                    return Optional.of(new BinanceOrderExecutor.OrderResult(true, String.valueOf(order.orderId()),
                            order.executedQuantity(), order.averagePrice(), null));
                }
                logger.error("âœ— Real exit order failed for {}: {}", pos.getPositionId(), result.error());
                return Optional.empty();
            }

            logger.info("âœ“ Real exit order executed: orderId={} qty={} @ price={}",
                    result.orderId(), result.executedQuantity(), result.averagePrice());
            syncPortfolioBalance(result.averagePrice());
            return Optional.of(result);
        } catch (Exception e) {
            logger.error("âœ— Error executing real exit order for {}: {}", pos.getPositionId(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** Refreshes USDT and base asset totals so the drawdown is measured on equity (issue #81). */
    private void syncPortfolioBalance(BigDecimal lastFillPrice) {
        if (portfolioManager.isEmpty() || orderExecutor.isEmpty()) {
            return;
        }
        PortfolioManager pm = portfolioManager.get();
        pm.updateMarketPrice(lastFillPrice);

        BinanceOrderExecutor.BalanceResult balance = orderExecutor.get().getBalance("USDT");
        if (!balance.success()) {
            logger.warn("Could not refresh USDT balance after order: {}", balance.error());
            return;
        }
        BinanceOrderExecutor.BalanceResult base = orderExecutor.get().getBalance(baseAsset());
        if (base.success()) {
            pm.updateBalances(balance.total(), base.total());
        } else {
            // Without the base balance, keep the last known base quantity rather than dropping it
            logger.warn("Could not refresh {} balance after order: {}", baseAsset(), base.error());
            pm.updateBalances(balance.total(), pm.getBaseQuantity());
        }
    }

    private String baseAsset() {
        return symbol.endsWith("USDT") ? symbol.substring(0, symbol.length() - 4) : symbol;
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
        return (int) closedPositions.stream().filter(ExitReason::isPerformanceTrade).count();
    }

    public synchronized int getWinTrades() {
        return (int) closedPositions.stream()
                .filter(ExitReason::isPerformanceTrade)
                .filter(p -> p.getPnL().compareTo(java.math.BigDecimal.ZERO) > 0)
                .count();
    }

    public synchronized BigDecimal getTotalPnL() {
        return closedPositions.stream()
                .filter(ExitReason::isPerformanceTrade)
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

