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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns the single open position and its lifecycle ({@link PositionState}).
 *
 * <p>Threading (issues #70, #111): every public entry point takes this object's lock, but no
 * Binance call runs while holding it. An order moves the position to PENDING_ENTRY or EXIT_PENDING
 * under the lock, the HTTP calls run on the order I/O executor (one dedicated thread, so orders stay
 * serialized), and the result is applied back under the lock after checking the position is still
 * the one, and in the state, the order was sent for. Ticks, candles and manual actions arriving
 * meanwhile only see the pending state and never send a second order.
 */
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

    // Volatile: set at startup, read by the order I/O thread without the lock
    private volatile Optional<BinanceOrderExecutor> orderExecutor = Optional.empty();
    private volatile Optional<PortfolioManager> portfolioManager = Optional.empty();
    private volatile Optional<BinanceSymbolValidationService> validationService = Optional.empty();
    private volatile Optional<OrderConfirmationManager> confirmationManager = Optional.empty();
    private volatile Optional<ConnectivityGuard> connectivityGuard = Optional.empty();
    private volatile Optional<AlertNotifier> alertNotifier = Optional.empty();
    private volatile String symbol = "BTCUSDT";
    private volatile boolean simulationMode = true;
    private boolean allowShort = false;
    private volatile boolean reconciliationComplete = true;

    // Exit retry backoff (issue #68): a failed exit must not resend an order on every tick
    static final Duration EXIT_RETRY_INITIAL_DELAY = Duration.ofSeconds(1);
    static final Duration EXIT_RETRY_MAX_DELAY = Duration.ofSeconds(60);
    static final int EXIT_FAILURE_ALERT_EVERY = 10;
    private volatile Supplier<Instant> clock = Instant::now;
    private int exitFailureCount = 0;
    private Instant nextExitAttemptAt = null;
    private volatile BinanceOrderExecutor.SymbolFilters cachedFilters = null;

    // OCO protection (issue #99): target and stop rest on Binance, so they survive the bot going down
    static final Duration PROTECTION_POLL_INTERVAL = Duration.ofSeconds(10);
    static final BigDecimal DEFAULT_STOP_LIMIT_OFFSET_PERCENT = new BigDecimal("0.1");
    private volatile boolean ocoEnabled = false;
    private volatile BigDecimal stopLimitOffsetPercent = DEFAULT_STOP_LIMIT_OFFSET_PERCENT;
    private boolean protectionActive = false;
    private BigDecimal protectionStopLimitPrice = null;
    private Instant nextProtectionPollAt = null;

    // Outbox (issue #111): every order is recorded before its request and updated after it
    private volatile OrderCommandStore orderCommands = OrderCommandStore.NONE;

    // Order I/O: created on first use unless injected
    private Executor orderIo;
    private ExecutorService ownedOrderIo;

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
     * Where order I/O runs. By default a dedicated single thread is created on first use; tests pass
     * a same-thread executor ({@code Runnable::run}) so an order completes before the call returns.
     */
    public synchronized void setOrderIoExecutor(Executor executor) {
        this.orderIo = Objects.requireNonNull(executor, "executor");
    }

    public void setOrderCommandStore(OrderCommandStore store) {
        this.orderCommands = Objects.requireNonNull(store, "store");
    }

    private synchronized Executor orderIo() {
        if (orderIo == null) {
            ownedOrderIo = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "PositionManager-OrderIO");
                thread.setDaemon(true);
                return thread;
            });
            orderIo = ownedOrderIo;
        }
        return orderIo;
    }

    /** Lets queued order I/O finish, then stops the thread this manager created. */
    public void shutdown() {
        ExecutorService owned;
        synchronized (this) {
            owned = ownedOrderIo;
        }
        if (owned == null) {
            return;
        }
        // Not under the lock: the queued tasks need it to apply their results
        owned.shutdown();
        try {
            if (!owned.awaitTermination(15, TimeUnit.SECONDS)) {
                logger.warn("Order I/O did not finish within 15s; interrupting it");
                owned.shutdownNow();
            }
        } catch (InterruptedException e) {
            owned.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs {@code task} on the order I/O executor. The task does its HTTP calls without the lock and
     * takes it only to read a snapshot and to apply the result. {@code onRejected} runs in the
     * caller (under the lock) when the executor no longer accepts work, so no state stays pending.
     */
    private void submitOrderIo(String description, Runnable task, Runnable onRejected) {
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                logger.error("Order I/O task failed: {}", description, e);
                alert(String.format("Order I/O task failed (%s): %s", description, e.getMessage()));
            }
        };
        try {
            orderIo().execute(guarded);
        } catch (RejectedExecutionException e) {
            logger.error("Order I/O executor rejected {}: {}", description, e.getMessage());
            onRejected.run();
        }
    }

    private void alert(String message) {
        alertNotifier.ifPresent(a -> a.alert(message));
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

    private boolean isCurrent(Position pos) {
        return openPosition.isPresent() && openPosition.get() == pos;
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
            alert(String.format(
                    "Order %s for position %s failed: %s", confirmation.orderId(), pos.getPositionId(), confirmation.status()));
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
            alert(String.format(
                    "Order %s for position %s could not be confirmed on Binance; position kept with qty=%s, check it manually",
                    confirmation.clientOrderId(), pos.getPositionId(), pos.getQuantity()));
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
        if (pos.getState() != PositionState.OPEN) {
            // A pending exit cancels the OCO first, and that cancel tells whether a leg filled
            logger.debug("Report for protection leg {} while {}: left to the pending exit",
                    confirmation.clientOrderId(), pos.getState());
            return;
        }
        if (confirmation.isPartial()) {
            logger.info("Protection leg {} partially filled: {}", confirmation.clientOrderId(), confirmation.filledQuantity());
            return;
        }
        if (confirmation.isFilled()) {
            boolean target = OcoOrderIds.target(symbol, pos.getPositionId()).equals(confirmation.clientOrderId());
            submitOrderIo("OCO fill " + pos.getPositionId(), () -> {
                // The report carries the last execution price; the order's average is the real exit price
                BigDecimal price = orderExecutor.get().queryOrder(symbol, confirmation.clientOrderId())
                        .map(BinanceOrderExecutor.QueriedOrder::averagePrice)
                        .filter(p -> p.signum() > 0)
                        .orElse(confirmation.lastFillPrice());
                synchronized (this) {
                    if (isCurrent(pos) && pos.getState() == PositionState.OPEN) {
                        closeByExchange(pos, target ? ExitReason.TARGET_HIT : ExitReason.STOP_LOSS,
                                confirmation.filledQuantity(), price, confirmation.confirmedAt());
                    }
                }
            }, () -> { });
            return;
        }
        // Cancelled, expired or unconfirmed: when a leg fills Binance expires the other one, and that
        // report may come first - ask Binance before dropping the protection
        submitOrderIo("OCO leg check " + pos.getPositionId(), () -> {
            ProtectionCheck check = checkProtection(pos);
            synchronized (this) {
                if (!isCurrent(pos) || pos.getState() != PositionState.OPEN || !protectionActive) {
                    return;
                }
                switch (check.kind()) {
                    case FILLED -> closeByExchange(pos, check.reason(), check.quantity(), check.price(), clock.get());
                    case GONE -> dropProtection(pos, "leg " + confirmation.clientOrderId() + " ended " + confirmation.status());
                    default -> { }
                }
            }
        }, () -> { });
    }

    private String entryClientOrderId(Position pos) {
        return OcoOrderIds.entry(symbol, pos.getPositionId());
    }

    public synchronized void processPrediction(PredictionVector prediction, CandleEvent candle) {
        if (openPosition.isPresent()) {
            Position pos = openPosition.get();
            pos.updatePrice(candle.close(), candle.closeTime());

            if (pos.getState() != PositionState.OPEN) {
                // An order is in flight: no exit and no new entry until its result is applied
                logger.info("Candle ignored for {} while {}", pos.getPositionId(), pos.getState());
                return;
            }

            boolean levelHit = pos.hasHitTarget() || pos.hasHitStopLoss();
            if (protectionActive) {
                // With the OCO on Binance, target and stop are the exchange's job
                if (levelHit) {
                    pollProtectionIfDue(pos);
                }
                if (!isCurrent(pos) || pos.getState() != PositionState.OPEN) {
                    return; // the poll already settled the position
                }
            } else if (levelHit) {
                exitAtLevel(pos);
                return;
            }

            if (shouldReversePosition(pos, prediction)) {
                closePosition(pos, candle.close(), candle.closeTime(), ExitReason.SIGNAL_REVERSAL);
                logger.info("â†’ REVERSAL: {} exit at {} for new signal ({})",
                        pos.getPositionId(), candle.close(), pos.getState());
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

        // A pending entry has nothing to exit yet; a pending exit already has its order in flight
        if (pos.getState() != PositionState.OPEN || !(pos.hasHitTarget() || pos.hasHitStopLoss())) {
            return;
        }
        if (protectionActive) {
            // The OCO on Binance exits the position; the poll only catches a lost fill or a gapped stop
            pollProtectionIfDue(pos);
            return;
        }
        exitAtLevel(pos);
    }

    /** Tick or candle exit at the position's latest price, when it crossed the target or the stop. */
    private void exitAtLevel(Position pos) {
        BigDecimal price = pos.getCurrentPrice();
        Instant time = pos.getLastUpdateTime();
        if (pos.hasHitTarget()) {
            if (closePosition(pos, price, time, ExitReason.TARGET_HIT)) {
                logger.info("âœ“ TARGET HIT: {} exit at {} ({}, P&L: {}%)",
                        pos.getPositionId(), price, pos.getState(), pos.getPnLPercent());
            }
        } else if (pos.hasHitStopLoss()) {
            if (closePosition(pos, price, time, ExitReason.STOP_LOSS)) {
                logger.warn("âœ— STOP LOSS: {} exit at {} ({}, P&L: {}%)",
                        pos.getPositionId(), price, pos.getState(), pos.getPnLPercent());
            }
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
        if (openPosition.get().getState() == PositionState.PENDING_ENTRY) {
            return new ManualBuyResult(true, "Manual BUY entry order sent");
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

    /**
     * Applies what startup reconciliation learned from the outbox to the restored position: an exit
     * Binance filled while the bot was down closes it, and an entry Binance never accepted fails it.
     * Nothing is sent to Binance.
     */
    public synchronized void applyReconciledCommands(List<OrderCommandReconciler.Resolution> resolutions) {
        for (OrderCommandReconciler.Resolution resolution : resolutions) {
            OrderCommand command = resolution.command();
            if (openPosition.isEmpty() || !openPosition.get().getPositionId().equals(command.positionId())) {
                continue;
            }
            Position pos = openPosition.get();
            if (command.type() == OrderCommand.Type.EXIT && resolution.outcome() == OrderCommandReconciler.Outcome.CONFIRMED) {
                BinanceOrderExecutor.QueriedOrder order = resolution.order().orElseThrow();
                if (!resolution.filled() || pos.getState() != PositionState.OPEN) {
                    alert(String.format("Exit %s of position %s ended %s while the bot was down; check it manually",
                            command.clientOrderId(), pos.getPositionId(), resolution.detail()));
                    continue;
                }
                ExitReason reason = Optional.ofNullable(ExitReason.parse(command.payload().get("reason")))
                        .orElse(ExitReason.MANUAL_CLOSE);
                BigDecimal price = order.averagePrice().signum() > 0 ? order.averagePrice() : pos.getCurrentPrice();
                logger.warn("Position {} was closed on Binance by exit {} before the restart: {} @ {}",
                        pos.getPositionId(), command.clientOrderId(), reason, price);
                unregisterProtectionLegs(pos);
                finishClose(pos, price, command.updatedAt(), reason);
            } else if (command.type() == OrderCommand.Type.ENTRY
                    && resolution.outcome() == OrderCommandReconciler.Outcome.FAILED
                    && pos.getQuantity().signum() == 0) {
                logger.warn("Entry {} of position {} never executed on Binance: {}",
                        command.clientOrderId(), pos.getPositionId(), resolution.detail());
                closePosition(pos, pos.getEntryPrice(), pos.getEntryTime(), ExitReason.ORDER_FAILED);
            }
        }
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

    /**
     * True when the position is closed, or its exit order is in flight (EXIT_PENDING) with the
     * dedicated order I/O thread; false when nothing could be sent or the exit failed.
     */
    public synchronized boolean closeManualPosition(BigDecimal price, java.time.Instant time) {
        if (openPosition.isEmpty() || price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (openPosition.get().getState() == PositionState.EXIT_PENDING) {
            return true; // the exit already in flight is the one the user asked for
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
            submitEntry(pos, signal);
        }
    }

    // ---- entry order ----

    enum EntryKind { FILLED, VALIDATION_FAILED, ORDER_FAILED, ERROR }

    record EntryOutcome(EntryKind kind, BigDecimal quantity, BinanceOrderExecutor.OrderResult result, String error) {
        static EntryOutcome of(EntryKind kind, BigDecimal quantity, String error) {
            return new EntryOutcome(kind, quantity, null, error);
        }
    }

    private void submitEntry(Position pos, Signal signal) {
        logger.info("Executing real order for position: {}", pos.getPositionId());
        String clientOrderId = entryClientOrderId(pos);
        BigDecimal entryPrice = pos.getEntryPrice();
        submitOrderIo("entry " + pos.getPositionId(), () -> {
            EntryOutcome outcome = executeEntry(pos.getPositionId(), signal, entryPrice, clientOrderId);
            synchronized (this) {
                applyEntryOutcome(pos, signal, outcome);
            }
        }, () -> applyEntryOutcome(pos, signal,
                EntryOutcome.of(EntryKind.ERROR, BigDecimal.ZERO, "order I/O executor is shut down")));
    }

    /** Runs on the order I/O thread without the lock. */
    private EntryOutcome executeEntry(String positionId, Signal signal, BigDecimal entryPrice, String clientOrderId) {
        BigDecimal quantity = BigDecimal.ZERO;
        boolean sent = false;
        try {
            PortfolioManager pm = portfolioManager.get();
            BigDecimal allocatedCapital = pm.getCurrentBalance()
                    .multiply(new java.math.BigDecimal("0.5"))
                    .setScale(2, java.math.RoundingMode.DOWN);

            quantity = allocatedCapital.divide(entryPrice, 8, java.math.RoundingMode.DOWN);

            if (validationService.isPresent()) {
                Optional<BigDecimal> validatedQty = validationService.get()
                        .validateAndAdjustQuantity(symbol, quantity, entryPrice, allocatedCapital);
                if (validatedQty.isEmpty()) {
                    return EntryOutcome.of(EntryKind.VALIDATION_FAILED, quantity, "quantity validation failed");
                }
                quantity = validatedQty.get();
            }

            logger.info("Executing real {} order: qty={} {} @ {}", signal, quantity, symbol, entryPrice);
            BigDecimal orderQuantity = quantity;
            orderCommands.record(clientOrderId, positionId, OrderCommand.Type.ENTRY, java.util.Map.of(
                    "symbol", symbol, "side", signal.name(), "quantity", quantity.toPlainString(),
                    "price", entryPrice.toPlainString()));
            // Before sending: a MARKET order fills at once and its report can beat the REST response
            confirmationManager.ifPresent(m ->
                    m.registerOrder(clientOrderId, symbol, signal.toString(), orderQuantity, entryPrice));

            BinanceOrderExecutor executor = orderExecutor.get();
            orderCommands.markSent(clientOrderId);
            sent = true;
            BinanceOrderExecutor.OrderResult result = signal == Signal.BUY
                    ? executor.executeBuyMarket(symbol, quantity, clientOrderId)
                    : executor.executeSellMarket(symbol, quantity, clientOrderId);

            if (!result.success()) {
                orderCommands.markFailed(clientOrderId, result.error());
                confirmationManager.ifPresent(m -> m.unregisterOrder(clientOrderId));
                return new EntryOutcome(EntryKind.ORDER_FAILED, quantity, result, result.error());
            }
            orderCommands.markConfirmed(clientOrderId);
            syncPortfolioBalance(result.averagePrice().signum() > 0 ? result.averagePrice() : entryPrice);
            return new EntryOutcome(EntryKind.FILLED, quantity, result, null);
        } catch (Exception e) {
            // Once sent, the outcome is unknown: the command stays SENT for the startup reconciliation
            if (!sent) {
                orderCommands.markFailed(clientOrderId, e.getMessage());
            }
            logger.error("âœ— Error executing real order for {}: {}", positionId, e.getMessage(), e);
            return EntryOutcome.of(EntryKind.ERROR, quantity, e.getMessage());
        }
    }

    /** Under the lock. */
    private void applyEntryOutcome(Position pos, Signal signal, EntryOutcome outcome) {
        if (!isCurrent(pos) || pos.getState() != PositionState.PENDING_ENTRY) {
            logger.error("Entry result {} for {} ignored: position is {}", outcome.kind(), pos.getPositionId(), pos.getState());
            if (outcome.kind() == EntryKind.FILLED) {
                alert(String.format("Entry order of position %s executed after the position became %s; check Binance manually",
                        pos.getPositionId(), pos.getState()));
            }
            return;
        }
        switch (outcome.kind()) {
            case FILLED -> {
                BinanceOrderExecutor.OrderResult result = outcome.result();
                pos.setTargetQuantity(outcome.quantity());
                pos.applyFill(result.executedQuantity());
                if (result.averagePrice().compareTo(BigDecimal.ZERO) > 0) {
                    pos.setEntryPrice(result.averagePrice());
                }
                pos.transitionTo(PositionState.OPEN);
                // Always: the quantity is what a restart needs to send the exit order (issue #64)
                positionPersistence.accept(pos);
                logger.info("âœ“ Real order executed: orderId={} qty={} @ price={}",
                        result.orderId(), result.executedQuantity(), result.averagePrice());
                if (result.averagePrice().signum() > 0) pos.updatePrice(result.averagePrice(), pos.getEntryTime());
                if (signal == Signal.BUY && pos.getQuantity().signum() > 0) {
                    submitProtectionPlacement(pos);
                }
            }
            case VALIDATION_FAILED -> {
                logger.error("âœ— Order rejected: quantity validation failed for {}", symbol);
                alert(String.format(
                        "Order rejected for position %s: quantity validation failed for %s", pos.getPositionId(), symbol));
                closePosition(pos, pos.getEntryPrice(), pos.getEntryTime(), ExitReason.VALIDATION_FAILED);
            }
            case ORDER_FAILED -> {
                pos.setTargetQuantity(outcome.quantity());
                logger.error("âœ— Real order failed: {}", outcome.error());
                alert(String.format("Real order failed for position %s: %s", pos.getPositionId(), outcome.error()));
                closePosition(pos, pos.getEntryPrice(), pos.getEntryTime(), ExitReason.ORDER_FAILED);
            }
            case ERROR -> {
                alert(String.format(
                        "Exception executing real order for position %s: %s", pos.getPositionId(), outcome.error()));
                closePosition(pos, pos.getEntryPrice(), pos.getEntryTime(), ExitReason.ERROR);
            }
        }
    }

    // ---- exit order ----

    private boolean closePosition(Position pos, BigDecimal exitPrice, java.time.Instant exitTime, ExitReason reason) {
        return closePosition(pos, exitPrice, exitTime, reason, true);
    }

    /**
     * Under the lock. Simulated (or empty) positions close at once. A real exit moves to
     * EXIT_PENDING and sends its order on the order I/O executor; returns whether the position is no
     * longer OPEN afterwards (closed, or its exit in flight).
     */
    private boolean closePosition(Position pos, BigDecimal exitPrice, java.time.Instant exitTime, ExitReason reason,
                                  boolean respectBackoff) {
        PositionState required = reason.countsInPerformance() ? PositionState.OPEN : pos.getState();
        if (pos.getState() != required || !pos.getState().canTransitionTo(PositionState.terminalFor(reason))) {
            // A pending entry has nothing to sell yet, and a pending exit already has its order in flight
            logger.debug("Close {} of {} ignored in state {}", reason, pos.getPositionId(), pos.getState());
            return false;
        }
        if (simulationMode || pos.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            finishClose(pos, exitPrice, exitTime, reason);
            return true;
        }

        Instant now = clock.get();
        if (respectBackoff && nextExitAttemptAt != null && now.isBefore(nextExitAttemptAt)) {
            return false;
        }
        pos.transitionTo(PositionState.EXIT_PENDING);
        positionPersistence.accept(pos);
        submitExit(pos, exitPrice, exitTime, reason);
        return pos.getState() != PositionState.OPEN;
    }

    enum ExitKind { FILLED, CLOSED_BY_EXCHANGE, FAILED }

    record ExitOutcome(ExitKind kind, boolean protectionCancelled, BinanceOrderExecutor.OrderResult result,
                       ProtectionCheck check) {
    }

    private void submitExit(Position pos, BigDecimal exitPrice, Instant exitTime, ExitReason reason) {
        submitOrderIo("exit " + pos.getPositionId(), () -> {
            boolean protection;
            BigDecimal quantity;
            synchronized (this) {
                if (!isCurrent(pos) || pos.getState() != PositionState.EXIT_PENDING) {
                    logger.warn("Exit {} of {} dropped before sending: position is {}", reason, pos.getPositionId(), pos.getState());
                    return;
                }
                // Read when the task starts, not when it was queued: an OCO placement queued before it
                // may have activated the protection meanwhile
                protection = protectionActive;
                quantity = pos.getQuantity();
            }
            ExitOutcome outcome = executeExit(pos, quantity, protection, reason);
            synchronized (this) {
                applyExitOutcome(pos, exitPrice, exitTime, reason, outcome);
            }
        }, () -> recordExitFailure(pos, reason, clock.get()));
    }

    /** Runs on the order I/O thread without the lock. */
    private ExitOutcome executeExit(Position pos, BigDecimal quantity, boolean protection, ExitReason reason) {
        boolean cancelled = false;
        try {
            if (protection) {
                // The OCO locks the BTC: it must be cancelled before the market exit can sell it
                ProtectionCheck cancel = cancelProtection(pos);
                if (cancel.kind() == ProtectionKind.FILLED) {
                    return new ExitOutcome(ExitKind.CLOSED_BY_EXCHANGE, false, null, cancel);
                }
                if (cancel.kind() != ProtectionKind.GONE) {
                    return new ExitOutcome(ExitKind.FAILED, false, null, cancel);
                }
                cancelled = true;
            }
            Optional<BinanceOrderExecutor.OrderResult> result = closeRealPosition(pos, quantity, reason);
            return result.isPresent()
                    ? new ExitOutcome(ExitKind.FILLED, cancelled, result.get(), null)
                    : new ExitOutcome(ExitKind.FAILED, cancelled, null, null);
        } catch (RuntimeException e) {
            logger.error("âœ— Error executing real exit for {}: {}", pos.getPositionId(), e.getMessage(), e);
            return new ExitOutcome(ExitKind.FAILED, cancelled, null, null);
        }
    }

    /** Under the lock. */
    private void applyExitOutcome(Position pos, BigDecimal exitPrice, Instant exitTime, ExitReason reason,
                                  ExitOutcome outcome) {
        if (outcome.protectionCancelled()) {
            resetProtection();
            unregisterProtectionLegs(pos);
        }
        if (!isCurrent(pos) || pos.getState() != PositionState.EXIT_PENDING) {
            logger.error("Exit result {} for {} ignored: position is {}", outcome.kind(), pos.getPositionId(), pos.getState());
            if (outcome.kind() == ExitKind.FILLED) {
                alert(String.format("Exit order of position %s executed after the position became %s; check Binance manually",
                        pos.getPositionId(), pos.getState()));
            }
            return;
        }
        switch (outcome.kind()) {
            case FILLED -> {
                BigDecimal average = outcome.result().averagePrice();
                finishClose(pos, average.compareTo(BigDecimal.ZERO) > 0 ? average : exitPrice, exitTime, reason);
            }
            case CLOSED_BY_EXCHANGE -> {
                ProtectionCheck check = outcome.check();
                logger.warn("Exit {} for {} not sent: Binance already closed it via the OCO ({})",
                        reason, pos.getPositionId(), check.reason());
                closeByExchange(pos, check.reason(), check.quantity(), check.price(), clock.get());
            }
            case FAILED -> recordExitFailure(pos, reason, clock.get());
        }
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
            alert(String.format(
                    "CRITICAL: exit order failed for position %s (reason: %s, %d failed attempts) - position remains OPEN and unmanaged, manual intervention required",
                    pos.getPositionId(), reason, failures));
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
        return OcoOrderIds.exit(symbol, pos.getPositionId());
    }

    /**
     * Quantity to sell when closing a BUY (issue #69): without BNB fees Binance takes the buy
     * commission from the base asset, so the free balance can be below the filled quantity.
     * Uses min(position qty, free base balance), rounded down to the LOT_SIZE step.
     */
    BigDecimal sellableExitQuantity(String positionId, BigDecimal positionQuantity) {
        BinanceOrderExecutor executor = orderExecutor.get();
        BigDecimal quantity = positionQuantity;

        String baseAsset = baseAsset();
        BinanceOrderExecutor.BalanceResult balance = executor.getBalance(baseAsset);
        if (balance != null && balance.success()) {
            if (balance.free().compareTo(quantity) < 0) {
                logger.warn("Free {} balance {} is below position {} qty {}; selling the free balance",
                        baseAsset, balance.free(), positionId, quantity);
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

    // Cached on success: filters rarely change
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

    /** Result of sending the OCO: the stop-limit price when placed, the error otherwise. */
    record ProtectionPlacement(boolean placed, BigDecimal stopLimit, String error) {
        static ProtectionPlacement failed(String error) {
            return new ProtectionPlacement(false, null, error);
        }
    }

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

    private boolean protectionApplicable(Position pos) {
        return ocoEnabled && !simulationMode && orderExecutor.isPresent()
                && pos.getSignal() == Signal.BUY && pos.getQuantity().signum() > 0;
    }

    /** Under the lock: places the SELL OCO of a freshly filled BUY on the order I/O executor. */
    private void submitProtectionPlacement(Position pos) {
        resetProtection();
        if (!protectionApplicable(pos)) {
            return;
        }
        BigDecimal market = pos.getCurrentPrice();
        BigDecimal quantity = pos.getQuantity();
        submitOrderIo("OCO placement " + pos.getPositionId(), () -> {
            ProtectionPlacement placement = placeProtection(pos, market, quantity);
            synchronized (this) {
                applyProtectionPlacement(pos, placement);
            }
        }, () -> protectionPlacementFailed(pos, "order I/O executor is shut down"));
    }

    /** Sends the OCO; no lock needed. On failure the caller alerts and leaves the tick exits in charge. */
    private ProtectionPlacement placeProtection(Position pos, BigDecimal market, BigDecimal positionQuantity) {
        String listId = OcoOrderIds.list(symbol, pos.getPositionId());
        try {
            BinanceOrderExecutor.SymbolFilters filters = symbolFilters();
            if (filters == null || filters.tickSize() == null || filters.tickSize().signum() <= 0) {
                return ProtectionPlacement.failed("PRICE_FILTER tickSize unavailable");
            }
            ProtectionPrices prices = protectionPrices(pos, filters.tickSize());
            // Binance rejects a SELL OCO unless target > last price > stop
            if (prices.target().compareTo(market) <= 0 || prices.stop().compareTo(market) >= 0) {
                return ProtectionPlacement.failed(String.format("price %s is already outside target %s / stop %s",
                        market, prices.target(), prices.stop()));
            }
            BigDecimal quantity = sellableExitQuantity(pos.getPositionId(), positionQuantity);
            if (quantity.signum() <= 0) {
                return ProtectionPlacement.failed("no sellable quantity");
            }
            if (filters.minNotional() != null && filters.minNotional().signum() > 0
                    && quantity.multiply(prices.stopLimit()).compareTo(filters.minNotional()) < 0) {
                return ProtectionPlacement.failed(String.format("qty %s at stop limit %s is below min notional %s",
                        quantity, prices.stopLimit(), filters.minNotional()));
            }

            orderCommands.record(listId, pos.getPositionId(), OrderCommand.Type.OCO, java.util.Map.of(
                    "symbol", symbol, "quantity", quantity.toPlainString(), "target", prices.target().toPlainString(),
                    "stop", prices.stop().toPlainString(), "stopLimit", prices.stopLimit().toPlainString()));
            registerProtectionLegs(pos);
            BinanceOrderExecutor executor = orderExecutor.get();
            orderCommands.markSent(listId);
            BinanceOrderExecutor.OcoResult result = executor.placeOcoSell(symbol, quantity, prices.target(),
                    prices.stop(), prices.stopLimit(), listId,
                    OcoOrderIds.target(symbol, pos.getPositionId()), OcoOrderIds.stop(symbol, pos.getPositionId()));
            // An error can be ambiguous (timeout after Binance accepted it): the list id settles it
            if (!result.success() && !executor.queryOrderList(listId).isExecuting()) {
                orderCommands.markFailed(listId, result.error());
                unregisterProtectionLegs(pos);
                return ProtectionPlacement.failed(result.error());
            }
            orderCommands.markConfirmed(listId);
            logger.info("OCO protection {} placed for {}: qty={} target={} stop={} limit={}",
                    listId, pos.getPositionId(), quantity, prices.target(), prices.stop(), prices.stopLimit());
            return new ProtectionPlacement(true, prices.stopLimit(), null);
        } catch (Exception e) {
            unregisterProtectionLegs(pos);
            return ProtectionPlacement.failed(e.getMessage());
        }
    }

    /** Under the lock. */
    private boolean applyProtectionPlacement(Position pos, ProtectionPlacement placement) {
        if (!placement.placed()) {
            return protectionPlacementFailed(pos, placement.error());
        }
        if (!isCurrent(pos) || pos.getState().isTerminal()) {
            // The position ended while the OCO was being sent: left alone it would sell coins later
            String listId = OcoOrderIds.list(symbol, pos.getPositionId());
            logger.error("OCO {} placed for {} which is already {}; cancelling it", listId, pos.getPositionId(), pos.getState());
            unregisterProtectionLegs(pos);
            submitOrderIo("orphan OCO cancel " + pos.getPositionId(), () -> {
                BinanceOrderExecutor.OrderListQuery cancel = cancelOrderList(pos.getPositionId(), listId);
                if (cancel.state() == BinanceOrderExecutor.OrderListQuery.State.ERROR) {
                    alert(String.format("Could not cancel OCO %s of closed position %s: %s",
                            listId, pos.getPositionId(), cancel.error()));
                }
            }, () -> alert(String.format("OCO %s of closed position %s was not cancelled", listId, pos.getPositionId())));
            return false;
        }
        activateProtection(placement.stopLimit());
        logger.info("OCO protection active for {}", pos.getPositionId());
        return true;
    }

    private boolean protectionPlacementFailed(Position pos, String error) {
        logger.error("OCO protection not placed for {}: {}; exits stay on ticks", pos.getPositionId(), error);
        alert(String.format(
                "OCO protection could not be placed for position %s: %s - target/stop depend on the bot running",
                pos.getPositionId(), error));
        return false;
    }

    /**
     * Where the OCO stands on Binance; no lock needed. EXECUTING is ACTIVE; a finished list is FILLED
     * when a leg filled and GONE when both legs ended unfilled (cancelled outside the bot); a list
     * Binance does not know is GONE; anything unanswered is UNKNOWN.
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

    /** DELETE of an OCO list through the outbox; an unanswered cancel stays SENT for reconciliation. */
    private BinanceOrderExecutor.OrderListQuery cancelOrderList(String positionId, String listId) {
        String key = OrderCommand.cancelKey(listId);
        orderCommands.record(key, positionId, OrderCommand.Type.CANCEL, java.util.Map.of("symbol", symbol, "list", listId));
        orderCommands.markSent(key);
        BinanceOrderExecutor.OrderListQuery cancel = orderExecutor.get().cancelOrderList(symbol, listId);
        switch (cancel.state()) {
            case FOUND -> orderCommands.markConfirmed(key);
            case NOT_FOUND -> orderCommands.markFailed(key, "list not open: " + cancel.error());
            case ERROR -> { }
        }
        return cancel;
    }

    /**
     * Cancels the OCO before a bot exit; no lock needed, the caller applies the result. GONE means the
     * BTC is free to sell; FILLED means Binance already sold it, so no market order may follow.
     */
    private ProtectionCheck cancelProtection(Position pos) {
        String listId = OcoOrderIds.list(symbol, pos.getPositionId());
        BinanceOrderExecutor.OrderListQuery cancel = cancelOrderList(pos.getPositionId(), listId);
        ProtectionCheck check = switch (cancel.state()) {
            case FOUND -> ProtectionCheck.of(ProtectionKind.GONE);
            case ERROR -> ProtectionCheck.of(ProtectionKind.UNKNOWN);
            // No longer open: executed, or cancelled outside the bot
            case NOT_FOUND -> checkProtection(pos);
        };
        if (check.kind() == ProtectionKind.GONE) {
            logger.info("OCO {} cancelled before exiting {}", listId, pos.getPositionId());
        } else if (check.kind() != ProtectionKind.FILLED) {
            logger.error("Could not cancel OCO {} for {}: {}", listId, pos.getPositionId(), cancel.error());
        }
        return check;
    }

    /**
     * Under the lock, when a tick or candle crosses target or stop while the OCO is active: at most
     * every {@link #PROTECTION_POLL_INTERVAL} asks Binance, to catch a fill whose report was lost. The
     * result exits at market when the OCO is gone, or when the price fell through the stop's limit
     * without filling it (a STOP_LOSS_LIMIT does not chase the price).
     */
    private void pollProtectionIfDue(Position pos) {
        Instant now = clock.get();
        if (nextProtectionPollAt != null && now.isBefore(nextProtectionPollAt)) {
            return;
        }
        nextProtectionPollAt = now.plus(PROTECTION_POLL_INTERVAL);
        submitOrderIo("OCO poll " + pos.getPositionId(), () -> {
            ProtectionCheck check = checkProtection(pos);
            synchronized (this) {
                applyProtectionPoll(pos, check);
            }
        }, () -> { });
    }

    /** Under the lock. */
    private void applyProtectionPoll(Position pos, ProtectionCheck check) {
        // A pending exit owns the OCO: its cancel settles whether a leg filled
        if (!isCurrent(pos) || pos.getState() != PositionState.OPEN || !protectionActive) {
            return;
        }
        switch (check.kind()) {
            case FILLED -> closeByExchange(pos, check.reason(), check.quantity(), check.price(), clock.get());
            case GONE -> {
                dropProtection(pos, "OCO no longer open on Binance");
                exitAtLevel(pos);
            }
            case ACTIVE -> {
                BigDecimal price = pos.getCurrentPrice();
                if (pos.hasHitStopLoss() && protectionStopLimitPrice != null
                        && price.compareTo(protectionStopLimitPrice) < 0) {
                    logger.warn("Price {} fell below stop limit {} of {} without a fill; exiting at market",
                            price, protectionStopLimitPrice, pos.getPositionId());
                    exitAtLevel(pos);
                }
            }
            default -> { }
        }
    }

    /** Under the lock. Binance closed the position through the OCO: recorded without sending an order. */
    private void closeByExchange(Position pos, ExitReason reason, BigDecimal quantity, BigDecimal price, Instant time) {
        BigDecimal exitPrice = price != null && price.signum() > 0 ? price : pos.getCurrentPrice();
        logger.warn("Position {} closed by Binance OCO: {} qty={} @ {}", pos.getPositionId(), reason, quantity, exitPrice);
        unregisterProtectionLegs(pos);
        finishClose(pos, exitPrice, time, reason);
        if (portfolioManager.isPresent() && orderExecutor.isPresent()) {
            submitOrderIo("balance sync", () -> syncPortfolioBalance(exitPrice), () -> { });
        }
    }

    private void dropProtection(Position pos, String why) {
        resetProtection();
        unregisterProtectionLegs(pos);
        logger.error("OCO protection lost for {}: {}; exits back on ticks", pos.getPositionId(), why);
        alert(String.format(
                "OCO protection lost for position %s (%s) - target/stop depend on the bot running",
                pos.getPositionId(), why));
    }

    /**
     * After a restart: an executing OCO keeps protecting, an executed one closes the position, a
     * missing one is placed again. An unanswered query assumes it active, so an exit still cancels
     * first and the next poll settles it.
     *
     * <p>Unlike the trading paths this talks to Binance while holding the lock: it runs once during
     * startup reconciliation, before market data and entries are enabled, and its caller needs the
     * outcome.
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
                resetProtection();
                boolean placed = applyProtectionPlacement(pos,
                        placeProtection(pos, pos.getCurrentPrice(), pos.getQuantity()));
                return placed ? ProtectionReconciliation.PLACED : ProtectionReconciliation.PLACEMENT_FAILED;
            }
            default -> {
                activateProtection(null);
                alert(String.format(
                        "Could not check the OCO of position %s on Binance; assuming it is active", pos.getPositionId()));
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

    /** Runs on the order I/O thread without the lock. */
    private Optional<BinanceOrderExecutor.OrderResult> closeRealPosition(Position pos, BigDecimal positionQuantity,
                                                                        ExitReason reason) {
        String clientOrderId = exitClientOrderId(pos);
        boolean sent = false;
        try {
            BinanceOrderExecutor executor = orderExecutor.get();
            BigDecimal quantity = positionQuantity;
            if (pos.getSignal() == Signal.BUY) {
                quantity = sellableExitQuantity(pos.getPositionId(), positionQuantity);
                if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                    logger.error("No sellable quantity for {} (position qty={})", pos.getPositionId(), positionQuantity);
                    return Optional.empty();
                }
            }
            Signal side = pos.getSignal() == Signal.BUY ? Signal.SELL : Signal.BUY;
            orderCommands.record(clientOrderId, pos.getPositionId(), OrderCommand.Type.EXIT, java.util.Map.of(
                    "symbol", symbol, "side", side.name(), "quantity", quantity.toPlainString(),
                    "reason", reason.name()));
            orderCommands.markSent(clientOrderId);
            sent = true;
            BinanceOrderExecutor.OrderResult result = side == Signal.SELL
                    ? executor.executeSellMarket(symbol, quantity, clientOrderId)
                    : executor.executeBuyMarket(symbol, quantity, clientOrderId);

            if (!result.success()) {
                // The error may be ambiguous (e.g. a timeout after Binance accepted the order)
                Optional<BinanceOrderExecutor.QueriedOrder> found = executor.queryOrder(symbol, clientOrderId);
                if (found.isPresent() && "FILLED".equals(found.get().status())) {
                    BinanceOrderExecutor.QueriedOrder order = found.get();
                    logger.warn("Exit order {} reported an error but is FILLED on Binance: {}", clientOrderId, result.error());
                    orderCommands.markConfirmed(clientOrderId);
                    syncPortfolioBalance(order.averagePrice());
                    return Optional.of(new BinanceOrderExecutor.OrderResult(true, String.valueOf(order.orderId()),
                            order.executedQuantity(), order.averagePrice(), null));
                }
                orderCommands.markFailed(clientOrderId, result.error());
                logger.error("âœ— Real exit order failed for {}: {}", pos.getPositionId(), result.error());
                return Optional.empty();
            }

            orderCommands.markConfirmed(clientOrderId);
            logger.info("âœ“ Real exit order executed: orderId={} qty={} @ price={}",
                    result.orderId(), result.executedQuantity(), result.averagePrice());
            syncPortfolioBalance(result.averagePrice());
            return Optional.of(result);
        } catch (Exception e) {
            if (!sent) {
                orderCommands.markFailed(clientOrderId, e.getMessage());
            }
            logger.error("âœ— Error executing real exit order for {}: {}", pos.getPositionId(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Refreshes USDT and base asset totals so the drawdown is measured on equity (issue #81). Runs on
     * the order I/O thread; PortfolioManager synchronizes itself.
     */
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
