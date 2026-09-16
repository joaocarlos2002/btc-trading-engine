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
    private BinanceOrderExecutor.SymbolFilters cachedFilters = null;

    // OCO protection (issue #99): target and stop rest on Binance, so they survive the bot going down
    static final Duration PROTECTION_POLL_INTERVAL = Duration.ofSeconds(10);
    static final BigDecimal DEFAULT_STOP_LIMIT_OFFSET_PERCENT = new BigDecimal("0.1");
    private boolean ocoEnabled = false;
    private BigDecimal stopLimitOffsetPercent = DEFAULT_STOP_LIMIT_OFFSET_PERCENT;
    private boolean protectionActive = false;
    private BigDecimal protectionStopLimitPrice = null;
    private Instant nextProtectionPollAt = null;

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

    public synchronized void setOrderConfirmationManager(OrderConfirmationManager manager) {
        this.confirmationManager = Optional.of(manager);
        manager.setConfirmationListener(this::onOrderConfirmed);
        // Reconciliation runs before the manager exists: legs it found active start being tracked now
        if (protectionActive && openPosition.isPresent()) {
            registerProtectionLegs(openPosition.get());
        }
        logger.info("Order confirmation manager attached");
    }

    /**
     * Real mode only. {@code stopLimitOffsetPercent} puts the stop's limit price below its trigger:
     * a STOP_LOSS_LIMIT whose limit equals the stop does not fill when the price gaps through it,
     * leaving the position unprotected; a small offset trades a bit of slippage for a fill.
     */
    public synchronized void setOcoProtection(boolean enabled, BigDecimal stopLimitOffsetPercent) {
        this.ocoEnabled = enabled;
        this.stopLimitOffsetPercent = stopLimitOffsetPercent;
        logger.info("OCO protection {} (stop-limit offset {}%)", enabled ? "enabled" : "disabled", stopLimitOffsetPercent);
    }

    public synchronized boolean isProtectionActive() {
        return protectionActive;
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
        if (openPosition.isPresent() && isProtectionLeg(openPosition.get(), confirmation.clientOrderId())) {
            onProtectionLegConfirmed(openPosition.get(), confirmation);
            return;
        }
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
            if (pos.getQuantity().signum() > 0) {
                // The REST response reported a fill: selling on a contradicting report could sell coins
                // the bot does not hold, so a human decides
                logger.error("Position {} keeps qty={} despite the failed report; check it manually",
                        pos.getPositionId(), pos.getQuantity());
                return;
            }
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

    private boolean isProtectionLeg(Position pos, String clientOrderId) {
        return OcoOrderIds.target(symbol, pos.getPositionId()).equals(clientOrderId)
                || OcoOrderIds.stop(symbol, pos.getPositionId()).equals(clientOrderId);
    }

    /** Binance executed (or dropped) a leg of the OCO: the position is closed without another order. */
    private void onProtectionLegConfirmed(Position pos, OrderConfirmationManager.OrderConfirmation confirmation) {
        if (!protectionActive) {
            // Reports of our own cancel before a bot exit
            logger.debug("Report for inactive protection leg {}: {}", confirmation.clientOrderId(), confirmation.status());
            return;
        }
        if (confirmation.isPartial()) {
            logger.info("Protection leg {} partially filled: {}", confirmation.clientOrderId(), confirmation.filledQuantity());
            return;
        }
        if (confirmation.isFilled()) {
            boolean target = OcoOrderIds.target(symbol, pos.getPositionId()).equals(confirmation.clientOrderId());
            // The report carries the last execution price; the order's average is the real exit price
            BigDecimal price = orderExecutor.get().queryOrder(symbol, confirmation.clientOrderId())
                    .map(BinanceOrderExecutor.QueriedOrder::averagePrice)
                    .filter(p -> p.signum() > 0)
                    .orElse(confirmation.lastFillPrice());
            closeByExchange(pos, target ? ExitReason.TARGET_HIT : ExitReason.STOP_LOSS,
                    confirmation.filledQuantity(), price, confirmation.confirmedAt());
            return;
        }
        // Cancelled, expired or unconfirmed: when a leg fills Binance expires the other one, and that
        // report may come first - ask Binance before dropping the protection
        ProtectionCheck check = checkProtection(pos);
        switch (check.kind()) {
            case FILLED -> closeByExchange(pos, check.reason(), check.quantity(), check.price(), clock.get());
            case GONE -> dropProtection(pos, "leg " + confirmation.clientOrderId() + " ended " + confirmation.status());
            default -> { }
        }
    }

    private String entryClientOrderId(Position pos) {
        return "btce-" + symbol + "-" + pos.getPositionId() + "-entry";
    }

    public synchronized void processPrediction(PredictionVector prediction, CandleEvent candle) {
        if (openPosition.isPresent()) {
            Position pos = openPosition.get();
            pos.updatePrice(candle.close(), candle.closeTime());

            // With the OCO on Binance, target and stop are the exchange's job
            boolean tickExits = !protectionActive
                    || ((pos.hasHitTarget() || pos.hasHitStopLoss()) && pollProtection(pos, candle.close()));
            if (openPosition.isEmpty()) {
                return; // the poll found the OCO executed
            }

            if (tickExits && pos.hasHitTarget()) {
                closePosition(pos, candle.close(), candle.closeTime(), ExitReason.TARGET_HIT);
                logger.info("âœ“ TARGET HIT: {} closed at {} (P&L: {}%)",
                        pos.getPositionId(), candle.close(), pos.getPnLPercent());
                return;
            }

            if (tickExits && pos.hasHitStopLoss()) {
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

        if (protectionActive
                && (!(pos.hasHitTarget() || pos.hasHitStopLoss()) || !pollProtection(pos, event.price()))) {
            // The OCO on Binance exits the position; no market order from ticks
            return;
        }

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
        resetProtection();
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
                stopLossPercent,
                PositionState.PENDING_ENTRY
        );
        boolean realOrder = !simulationMode && orderExecutor.isPresent();
        if (!realOrder) {
            // A simulated entry fills at the signal price
            pos.transitionTo(PositionState.OPEN);
        }
        openPosition = Optional.of(pos);
        resetExitBackoff();
        resetProtection();

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

        if (realOrder) {
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
                pos.transitionTo(PositionState.OPEN);
                // Always: the quantity is what a restart needs to send the exit order (issue #64)
                positionPersistence.accept(pos);
                syncPortfolioBalance(pos.getEntryPrice());
                logger.info("âœ“ Real order executed: orderId={} qty={} @ price={}",
                        result.orderId(), result.executedQuantity(), result.averagePrice());
                if (result.averagePrice().signum() > 0) pos.updatePrice(result.averagePrice(), pos.getEntryTime());
                if (signal == Signal.BUY && pos.getQuantity().signum() > 0) {
                    placeProtection(pos);
                }
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
            if (pos.getState() == PositionState.PENDING_ENTRY) {
                closePosition(pos, pos.getEntryPrice(), pos.getEntryTime(), ExitReason.ERROR);
            }
        }
    }

    private boolean closePosition(Position pos, BigDecimal exitPrice, java.time.Instant exitTime, ExitReason reason) {
        return closePosition(pos, exitPrice, exitTime, reason, true);
    }

    private boolean closePosition(Position pos, BigDecimal exitPrice, java.time.Instant exitTime, ExitReason reason,
                                  boolean respectBackoff) {
        PositionState required = reason.countsInPerformance() ? PositionState.OPEN : pos.getState();
        if (pos.getState() != required || !pos.getState().canTransitionTo(PositionState.terminalFor(reason))) {
            // A pending entry has nothing to sell yet, and a pending exit already has its order in flight
            logger.debug("Close {} of {} ignored in state {}", reason, pos.getPositionId(), pos.getState());
            return false;
        }
        if (!simulationMode && pos.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
            Instant now = clock.get();
            if (respectBackoff && nextExitAttemptAt != null && now.isBefore(nextExitAttemptAt)) {
                return false;
            }
            pos.transitionTo(PositionState.EXIT_PENDING);
            positionPersistence.accept(pos);
            if (protectionActive) {
                // The OCO locks the BTC: it must be cancelled before the market exit can sell it
                ProtectionCheck cancel = cancelProtection(pos);
                if (cancel.kind() == ProtectionKind.FILLED) {
                    logger.warn("Exit {} for {} not sent: Binance already closed it via the OCO ({})",
                            reason, pos.getPositionId(), cancel.reason());
                    closeByExchange(pos, cancel.reason(), cancel.quantity(), cancel.price(), now);
                    return true;
                }
                if (cancel.kind() != ProtectionKind.GONE) {
                    recordExitFailure(pos, reason, now);
                    return false;
                }
            }
            Optional<BinanceOrderExecutor.OrderResult> result = closeRealPosition(pos);
            if (result.isEmpty()) {
                recordExitFailure(pos, reason, now);
                return false;
            }
            if (result.get().averagePrice().compareTo(BigDecimal.ZERO) > 0) {
                exitPrice = result.get().averagePrice();
            }
        }

        finishClose(pos, exitPrice, exitTime, reason);
        return true;
    }

    private void recordExitFailure(Position pos, ExitReason reason, Instant now) {
        if (pos.getState() == PositionState.EXIT_PENDING) {
            pos.transitionTo(PositionState.OPEN);
            positionPersistence.accept(pos);
        }
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
    }

    private void finishClose(Position pos, BigDecimal exitPrice, Instant exitTime, ExitReason reason) {
        resetExitBackoff();
        resetProtection();
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

    private BigDecimal exitStepSize() {
        BinanceOrderExecutor.SymbolFilters filters = symbolFilters();
        return filters == null ? null : filters.stepSize();
    }

    // Cached on success: filters rarely change and exits run inside this lock
    private BinanceOrderExecutor.SymbolFilters symbolFilters() {
        if (cachedFilters == null) {
            BinanceOrderExecutor.SymbolFilters filters = orderExecutor.get().getSymbolFilters(symbol);
            if (filters != null && filters.stepSize() != null && filters.stepSize().compareTo(BigDecimal.ZERO) > 0) {
                cachedFilters = filters;
            }
        }
        return cachedFilters;
    }

    // ---- OCO protection (issue #99) ----

    enum ProtectionKind { ACTIVE, FILLED, GONE, UNKNOWN }

    /** FILLED carries the executed leg's reason, quantity and average price. */
    record ProtectionCheck(ProtectionKind kind, ExitReason reason, BigDecimal quantity, BigDecimal price) {
        static ProtectionCheck of(ProtectionKind kind) {
            return new ProtectionCheck(kind, null, null, null);
        }
    }

    /** Outcome of {@link #reconcileProtection()} after a restart. */
    public enum ProtectionReconciliation { NOT_APPLICABLE, ACTIVE, CLOSED_BY_EXCHANGE, PLACED, PLACEMENT_FAILED, UNKNOWN }

    record ProtectionPrices(BigDecimal target, BigDecimal stop, BigDecimal stopLimit) {}

    /**
     * Target rounded up and stop rounded down to the tick, so rounding never tightens either level.
     * The limit sits {@code stopLimitOffsetPercent} below the stop and at least one tick under it.
     */
    ProtectionPrices protectionPrices(Position pos, BigDecimal tickSize) {
        BigDecimal target = roundToTick(pos.getTargetPrice(), tickSize, java.math.RoundingMode.UP);
        BigDecimal stop = roundToTick(pos.getStopLossPrice(), tickSize, java.math.RoundingMode.DOWN);
        BigDecimal factor = BigDecimal.ONE.subtract(
                stopLimitOffsetPercent.divide(BigDecimal.valueOf(100), 8, java.math.RoundingMode.HALF_UP));
        BigDecimal stopLimit = roundToTick(stop.multiply(factor), tickSize, java.math.RoundingMode.DOWN);
        if (stopLimit.compareTo(stop) >= 0) {
            stopLimit = stop.subtract(tickSize);
        }
        return new ProtectionPrices(target, stop, stopLimit);
    }

    private static BigDecimal roundToTick(BigDecimal price, BigDecimal tickSize, java.math.RoundingMode mode) {
        return price.divide(tickSize, 0, mode).multiply(tickSize).stripTrailingZeros();
    }

    /** Places the SELL OCO for an open BUY; on failure alerts and leaves the tick exits in charge. */
    private boolean placeProtection(Position pos) {
        resetProtection();
        if (!ocoEnabled || simulationMode || orderExecutor.isEmpty()
                || pos.getSignal() != Signal.BUY || pos.getQuantity().signum() <= 0) {
            return false;
        }
        String listId = OcoOrderIds.list(symbol, pos.getPositionId());
        try {
            BinanceOrderExecutor.SymbolFilters filters = symbolFilters();
            if (filters == null || filters.tickSize() == null || filters.tickSize().signum() <= 0) {
                return protectionPlacementFailed(pos, "PRICE_FILTER tickSize unavailable");
            }
            ProtectionPrices prices = protectionPrices(pos, filters.tickSize());
            BigDecimal market = pos.getCurrentPrice();
            // Binance rejects a SELL OCO unless target > last price > stop
            if (prices.target().compareTo(market) <= 0 || prices.stop().compareTo(market) >= 0) {
                return protectionPlacementFailed(pos, String.format("price %s is already outside target %s / stop %s",
                        market, prices.target(), prices.stop()));
            }
            BigDecimal quantity = sellableExitQuantity(pos);
            if (quantity.signum() <= 0) {
                return protectionPlacementFailed(pos, "no sellable quantity");
            }
            if (filters.minNotional() != null && filters.minNotional().signum() > 0
                    && quantity.multiply(prices.stopLimit()).compareTo(filters.minNotional()) < 0) {
                return protectionPlacementFailed(pos, String.format("qty %s at stop limit %s is below min notional %s",
                        quantity, prices.stopLimit(), filters.minNotional()));
            }

            registerProtectionLegs(pos);
            BinanceOrderExecutor executor = orderExecutor.get();
            BinanceOrderExecutor.OcoResult result = executor.placeOcoSell(symbol, quantity, prices.target(),
                    prices.stop(), prices.stopLimit(), listId,
                    OcoOrderIds.target(symbol, pos.getPositionId()), OcoOrderIds.stop(symbol, pos.getPositionId()));
            // An error can be ambiguous (timeout after Binance accepted it): the list id settles it
            if (!result.success() && !executor.queryOrderList(listId).isExecuting()) {
                unregisterProtectionLegs(pos);
                return protectionPlacementFailed(pos, result.error());
            }
            activateProtection(prices.stopLimit());
            logger.info("OCO protection {} active for {}: qty={} target={} stop={} limit={}",
                    listId, pos.getPositionId(), quantity, prices.target(), prices.stop(), prices.stopLimit());
            return true;
        } catch (Exception e) {
            unregisterProtectionLegs(pos);
            return protectionPlacementFailed(pos, e.getMessage());
        }
    }

    private boolean protectionPlacementFailed(Position pos, String error) {
        logger.error("OCO protection not placed for {}: {}; exits stay on ticks", pos.getPositionId(), error);
        alertNotifier.ifPresent(a -> a.alert(String.format(
                "OCO protection could not be placed for position %s: %s - target/stop depend on the bot running",
                pos.getPositionId(), error)));
        return false;
    }

    /**
     * Where the OCO stands on Binance. EXECUTING is ACTIVE; a finished list is FILLED when a leg
     * filled and GONE when both legs ended unfilled (cancelled outside the bot); a list Binance does
     * not know is GONE; anything unanswered is UNKNOWN.
     */
    private ProtectionCheck checkProtection(Position pos) {
        BinanceOrderExecutor executor = orderExecutor.get();
        BinanceOrderExecutor.OrderListQuery list = executor.queryOrderList(OcoOrderIds.list(symbol, pos.getPositionId()));
        if (list.state() == BinanceOrderExecutor.OrderListQuery.State.ERROR) {
            return ProtectionCheck.of(ProtectionKind.UNKNOWN);
        }
        if (list.state() == BinanceOrderExecutor.OrderListQuery.State.NOT_FOUND) {
            return ProtectionCheck.of(ProtectionKind.GONE);
        }
        if (list.isExecuting()) {
            return ProtectionCheck.of(ProtectionKind.ACTIVE);
        }
        Optional<BinanceOrderExecutor.QueriedOrder> target =
                executor.queryOrder(symbol, OcoOrderIds.target(symbol, pos.getPositionId()));
        Optional<BinanceOrderExecutor.QueriedOrder> stop =
                executor.queryOrder(symbol, OcoOrderIds.stop(symbol, pos.getPositionId()));
        if (target.isPresent() && "FILLED".equals(target.get().status())) {
            return new ProtectionCheck(ProtectionKind.FILLED, ExitReason.TARGET_HIT,
                    target.get().executedQuantity(), target.get().averagePrice());
        }
        if (stop.isPresent() && "FILLED".equals(stop.get().status())) {
            return new ProtectionCheck(ProtectionKind.FILLED, ExitReason.STOP_LOSS,
                    stop.get().executedQuantity(), stop.get().averagePrice());
        }
        return ProtectionCheck.of(target.isPresent() && stop.isPresent() ? ProtectionKind.GONE : ProtectionKind.UNKNOWN);
    }

    /**
     * Cancels the OCO before a bot exit. GONE means the BTC is free to sell; FILLED means Binance
     * already sold it, so no market order may follow.
     */
    private ProtectionCheck cancelProtection(Position pos) {
        String listId = OcoOrderIds.list(symbol, pos.getPositionId());
        BinanceOrderExecutor.OrderListQuery cancel = orderExecutor.get().cancelOrderList(symbol, listId);
        ProtectionCheck check = switch (cancel.state()) {
            case FOUND -> ProtectionCheck.of(ProtectionKind.GONE);
            case ERROR -> ProtectionCheck.of(ProtectionKind.UNKNOWN);
            // No longer open: executed, or cancelled outside the bot
            case NOT_FOUND -> checkProtection(pos);
        };
        if (check.kind() == ProtectionKind.GONE) {
            logger.info("OCO {} cancelled before exiting {}", listId, pos.getPositionId());
            resetProtection();
            unregisterProtectionLegs(pos);
        } else if (check.kind() != ProtectionKind.FILLED) {
            logger.error("Could not cancel OCO {} for {}: {}", listId, pos.getPositionId(), cancel.error());
        }
        return check;
    }

    /**
     * Runs when a tick or candle crosses target or stop while the OCO is active, at most every
     * {@link #PROTECTION_POLL_INTERVAL}: catches a fill whose report was lost. True means the tick
     * logic must exit at market - the OCO is gone, or the price fell through the stop's limit
     * without filling it (a STOP_LOSS_LIMIT does not chase the price).
     */
    private boolean pollProtection(Position pos, BigDecimal price) {
        Instant now = clock.get();
        if (nextProtectionPollAt != null && now.isBefore(nextProtectionPollAt)) {
            return false;
        }
        nextProtectionPollAt = now.plus(PROTECTION_POLL_INTERVAL);
        ProtectionCheck check = checkProtection(pos);
        switch (check.kind()) {
            case FILLED -> {
                closeByExchange(pos, check.reason(), check.quantity(), check.price(), now);
                return false;
            }
            case GONE -> {
                dropProtection(pos, "OCO no longer open on Binance");
                return true;
            }
            case ACTIVE -> {
                if (pos.hasHitStopLoss() && protectionStopLimitPrice != null
                        && price.compareTo(protectionStopLimitPrice) < 0) {
                    logger.warn("Price {} fell below stop limit {} of {} without a fill; exiting at market",
                            price, protectionStopLimitPrice, pos.getPositionId());
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /** Binance closed the position through the OCO: recorded locally without sending an order. */
    private void closeByExchange(Position pos, ExitReason reason, BigDecimal quantity, BigDecimal price, Instant time) {
        BigDecimal exitPrice = price != null && price.signum() > 0 ? price : pos.getCurrentPrice();
        logger.warn("Position {} closed by Binance OCO: {} qty={} @ {}", pos.getPositionId(), reason, quantity, exitPrice);
        unregisterProtectionLegs(pos);
        syncPortfolioBalance(exitPrice);
        finishClose(pos, exitPrice, time, reason);
    }

    private void dropProtection(Position pos, String why) {
        resetProtection();
        unregisterProtectionLegs(pos);
        logger.error("OCO protection lost for {}: {}; exits back on ticks", pos.getPositionId(), why);
        alertNotifier.ifPresent(a -> a.alert(String.format(
                "OCO protection lost for position %s (%s) - target/stop depend on the bot running",
                pos.getPositionId(), why)));
    }

    /**
     * After a restart: an executing OCO keeps protecting, an executed one closes the position, a
     * missing one is placed again. An unanswered query assumes it active, so an exit still cancels
     * first and the next poll settles it.
     */
    public synchronized ProtectionReconciliation reconcileProtection() {
        if (!ocoEnabled || simulationMode || orderExecutor.isEmpty() || openPosition.isEmpty()) {
            return ProtectionReconciliation.NOT_APPLICABLE;
        }
        Position pos = openPosition.get();
        if (pos.getSignal() != Signal.BUY || pos.getQuantity().signum() <= 0) {
            return ProtectionReconciliation.NOT_APPLICABLE;
        }
        ProtectionCheck check = checkProtection(pos);
        switch (check.kind()) {
            case ACTIVE -> {
                BinanceOrderExecutor.SymbolFilters filters = symbolFilters();
                boolean hasTick = filters != null && filters.tickSize() != null && filters.tickSize().signum() > 0;
                activateProtection(hasTick ? protectionPrices(pos, filters.tickSize()).stopLimit() : null);
                registerProtectionLegs(pos);
                logger.info("OCO protection of {} is active on Binance", pos.getPositionId());
                return ProtectionReconciliation.ACTIVE;
            }
            case FILLED -> {
                closeByExchange(pos, check.reason(), check.quantity(), check.price(), clock.get());
                return ProtectionReconciliation.CLOSED_BY_EXCHANGE;
            }
            case GONE -> {
                return placeProtection(pos) ? ProtectionReconciliation.PLACED : ProtectionReconciliation.PLACEMENT_FAILED;
            }
            default -> {
                activateProtection(null);
                alertNotifier.ifPresent(a -> a.alert(String.format(
                        "Could not check the OCO of position %s on Binance; assuming it is active", pos.getPositionId())));
                return ProtectionReconciliation.UNKNOWN;
            }
        }
    }

    private void activateProtection(BigDecimal stopLimitPrice) {
        protectionActive = true;
        protectionStopLimitPrice = stopLimitPrice;
        nextProtectionPollAt = null;
    }

    private void resetProtection() {
        protectionActive = false;
        protectionStopLimitPrice = null;
        nextProtectionPollAt = null;
    }

    // Before sending: a leg can fill at once and its report must find the tracker
    private void registerProtectionLegs(Position pos) {
        confirmationManager.ifPresent(m -> {
            m.registerProtectiveOrder(OcoOrderIds.target(symbol, pos.getPositionId()), symbol);
            m.registerProtectiveOrder(OcoOrderIds.stop(symbol, pos.getPositionId()), symbol);
        });
    }

    private void unregisterProtectionLegs(Position pos) {
        confirmationManager.ifPresent(m -> {
            m.unregisterOrder(OcoOrderIds.target(symbol, pos.getPositionId()));
            m.unregisterOrder(OcoOrderIds.stop(symbol, pos.getPositionId()));
        });
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

