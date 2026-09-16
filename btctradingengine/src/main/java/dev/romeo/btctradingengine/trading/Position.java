package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.prediction.Signal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public class Position {
    private final String positionId;
    private final Signal signal;                                    // BUY ou SELL
    private BigDecimal entryPrice;
    private final Instant entryTime;
    private final BigDecimal targetPercent;                         // ex: 2.0 = +2%
    private final BigDecimal stopLossPercent;                       // ex: 1.5 = -1.5%

    private BigDecimal currentPrice;
    private Instant lastUpdateTime;
    private PositionState state;
    private BigDecimal exitPrice;
    private Instant exitTime;
    private ExitReason exitReason;
    private BigDecimal quantity = BigDecimal.ZERO;                  // quantidade preenchida (cumulativa)
    private BigDecimal targetQuantity = BigDecimal.ZERO;            // quantidade total solicitada na ordem

    public enum PositionStatus {
        OPEN, CLOSED, STOPPED_OUT
    }

    /** Starts OPEN: restored rows and tests hold an entry that already executed. */
    public Position(String positionId, Signal signal, BigDecimal entryPrice,
                   Instant entryTime, BigDecimal targetPercent, BigDecimal stopLossPercent) {
        this(positionId, signal, entryPrice, entryTime, targetPercent, stopLossPercent, PositionState.OPEN);
    }

    public Position(String positionId, Signal signal, BigDecimal entryPrice,
                   Instant entryTime, BigDecimal targetPercent, BigDecimal stopLossPercent,
                   PositionState initialState) {
        this.positionId = positionId;
        this.signal = signal;
        this.entryPrice = entryPrice;
        this.entryTime = entryTime;
        this.targetPercent = targetPercent;
        this.stopLossPercent = stopLossPercent;
        this.currentPrice = entryPrice;
        this.lastUpdateTime = entryTime;
        this.state = java.util.Objects.requireNonNull(initialState, "initialState");
    }

    /**
     * Moves to {@code target} or throws: an illegal transition means the caller's view of the
     * position is wrong, and acting on it could send an order that should not exist.
     */
    public void transitionTo(PositionState target) {
        if (!state.canTransitionTo(target)) {
            throw new PositionState.IllegalTransitionException(positionId, state, target);
        }
        this.state = target;
    }

    public void updatePrice(BigDecimal price, Instant time) {
        this.currentPrice = price;
        this.lastUpdateTime = time;
    }

    public boolean hasHitTarget() {
        if (state.isTerminal()) {
            return false;
        }

        if (signal == Signal.BUY) {
            BigDecimal target = entryPrice.multiply(
                BigDecimal.ONE.add(targetPercent.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP))
            );
            return currentPrice.compareTo(target) >= 0;
        } else if (signal == Signal.SELL) {
            BigDecimal target = entryPrice.multiply(
                BigDecimal.ONE.subtract(targetPercent.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP))
            );
            return currentPrice.compareTo(target) <= 0;
        }
        return false;
    }

    public boolean hasHitStopLoss() {
        if (state.isTerminal()) {
            return false;
        }

        if (signal == Signal.BUY) {
            BigDecimal stopLoss = entryPrice.multiply(
                BigDecimal.ONE.subtract(stopLossPercent.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP))
            );
            return currentPrice.compareTo(stopLoss) <= 0;
        } else if (signal == Signal.SELL) {
            BigDecimal stopLoss = entryPrice.multiply(
                BigDecimal.ONE.add(stopLossPercent.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP))
            );
            return currentPrice.compareTo(stopLoss) >= 0;
        }
        return false;
    }

    public void closeAtTarget(BigDecimal exitPrice, Instant exitTime) {
        close(exitPrice, exitTime, ExitReason.TARGET_HIT);
    }

    public void closeAtStopLoss(BigDecimal exitPrice, Instant exitTime) {
        close(exitPrice, exitTime, ExitReason.STOP_LOSS);
    }

    public void closeManual(BigDecimal exitPrice, Instant exitTime) {
        close(exitPrice, exitTime, ExitReason.MANUAL_CLOSE);
    }

    /** Restores a row from the trade journal; an unknown stored reason becomes null. */
    public void restoreClosed(BigDecimal exitPrice, Instant exitTime, String reason) {
        close(exitPrice, exitTime, ExitReason.parse(reason));
    }

    /** CLOSED, or FAILED for an entry that never executed; throws from a state that cannot close. */
    public void close(BigDecimal exitPrice, Instant exitTime, ExitReason reason) {
        transitionTo(PositionState.terminalFor(reason));
        this.exitPrice = exitPrice;
        this.exitTime = exitTime;
        this.exitReason = reason;
    }

    public BigDecimal getPnL() {
        if (exitPrice == null) {
            return currentPrice.subtract(entryPrice);
        }

        if (signal == Signal.BUY) {
            return exitPrice.subtract(entryPrice);
        } else if (signal == Signal.SELL) {
            return entryPrice.subtract(exitPrice);
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal getPnLPercent() {
        if (entryPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return getPnL().divide(entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    // Getters
    public String getPositionId() { return positionId; }
    public Signal getSignal() { return signal; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public Instant getEntryTime() { return entryTime; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public Instant getLastUpdateTime() { return lastUpdateTime; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public Instant getExitTime() { return exitTime; }
    public ExitReason getExitReason() { return exitReason; }
    public PositionStatus getStatus() { return state.isTerminal() ? PositionStatus.CLOSED : PositionStatus.OPEN; }
    public PositionState getState() { return state; }
    /** Not closed or failed yet: a pending entry or exit still counts as open. */
    public boolean isOpen() { return !state.isTerminal(); }
    public BigDecimal getTargetPercent() { return targetPercent; }
    public BigDecimal getStopLossPercent() { return stopLossPercent; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getTargetQuantity() { return targetQuantity; }
    public void setTargetQuantity(BigDecimal targetQuantity) { this.targetQuantity = targetQuantity; }

    /**
     * A cumulative fill never shrinks: a late, duplicated or empty confirmation must not drop the
     * quantity already known, or the exit order would sell less than was bought (issue #63).
     */
    public void applyFill(BigDecimal cumulativeFilledQuantity) {
        if (cumulativeFilledQuantity != null && cumulativeFilledQuantity.compareTo(quantity) > 0) {
            this.quantity = cumulativeFilledQuantity;
        }
    }

    public BigDecimal getRemainingQuantity() {
        BigDecimal remaining = targetQuantity.subtract(quantity);
        return remaining.compareTo(BigDecimal.ZERO) > 0 ? remaining : BigDecimal.ZERO;
    }

    public boolean isFullyFilled() {
        return targetQuantity.compareTo(BigDecimal.ZERO) > 0 && quantity.compareTo(targetQuantity) >= 0;
    }

    public BigDecimal getTargetPrice() {
        BigDecimal percentage = targetPercent.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        return signal == Signal.BUY
                ? entryPrice.multiply(BigDecimal.ONE.add(percentage))
                : entryPrice.multiply(BigDecimal.ONE.subtract(percentage));
    }

    public BigDecimal getStopLossPrice() {
        BigDecimal percentage = stopLossPercent.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        return signal == Signal.BUY
                ? entryPrice.multiply(BigDecimal.ONE.subtract(percentage))
                : entryPrice.multiply(BigDecimal.ONE.add(percentage));
    }

    @Override
    public String toString() {
        return String.format(
                "%s: %s @ %.2f (target: +%.1f%%, stop: -%.1f%%) | current: %.2f | P&L: %s",
                positionId, signal, entryPrice, targetPercent, stopLossPercent,
                currentPrice,
                getPnLPercent().setScale(2, RoundingMode.HALF_UP) + "%"
        );
    }
}

