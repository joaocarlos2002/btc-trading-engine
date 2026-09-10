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
    private PositionStatus status;
    private BigDecimal exitPrice;
    private Instant exitTime;
    private String exitReason;                                      // "TARGET_HIT", "STOP_LOSS", "MANUAL_CLOSE"
    private BigDecimal quantity = BigDecimal.ZERO;                  // quantidade preenchida (cumulativa)
    private BigDecimal targetQuantity = BigDecimal.ZERO;            // quantidade total solicitada na ordem

    public enum PositionStatus {
        OPEN, CLOSED, STOPPED_OUT
    }

    public Position(String positionId, Signal signal, BigDecimal entryPrice,
                   Instant entryTime, BigDecimal targetPercent, BigDecimal stopLossPercent) {
        this.positionId = positionId;
        this.signal = signal;
        this.entryPrice = entryPrice;
        this.entryTime = entryTime;
        this.targetPercent = targetPercent;
        this.stopLossPercent = stopLossPercent;
        this.currentPrice = entryPrice;
        this.lastUpdateTime = entryTime;
        this.status = PositionStatus.OPEN;
    }

    public void updatePrice(BigDecimal price, Instant time) {
        this.currentPrice = price;
        this.lastUpdateTime = time;
    }

    public boolean hasHitTarget() {
        if (status != PositionStatus.OPEN) {
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
        if (status != PositionStatus.OPEN) {
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
        close(exitPrice, exitTime, "TARGET_HIT");
    }

    public void closeAtStopLoss(BigDecimal exitPrice, Instant exitTime) {
        close(exitPrice, exitTime, "STOP_LOSS");
    }

    public void closeManual(BigDecimal exitPrice, Instant exitTime) {
        close(exitPrice, exitTime, "MANUAL_CLOSE");
    }

    public void restoreClosed(BigDecimal exitPrice, Instant exitTime, String reason) {
        close(exitPrice, exitTime, reason);
    }

    private void close(BigDecimal exitPrice, Instant exitTime, String reason) {
        this.exitPrice = exitPrice;
        this.exitTime = exitTime;
        this.exitReason = reason;
        this.status = PositionStatus.CLOSED;
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
    public BigDecimal getExitPrice() { return exitPrice; }
    public Instant getExitTime() { return exitTime; }
    public String getExitReason() { return exitReason; }
    public PositionStatus getStatus() { return status; }
    public boolean isOpen() { return status == PositionStatus.OPEN; }
    public BigDecimal getTargetPercent() { return targetPercent; }
    public BigDecimal getStopLossPercent() { return stopLossPercent; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getTargetQuantity() { return targetQuantity; }
    public void setTargetQuantity(BigDecimal targetQuantity) { this.targetQuantity = targetQuantity; }

    public void applyFill(BigDecimal cumulativeFilledQuantity) {
        this.quantity = cumulativeFilledQuantity;
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

