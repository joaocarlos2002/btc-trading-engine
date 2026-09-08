package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.prediction.Signal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public class Trade {
    private final String tradeId;
    private final Signal signal;
    private final BigDecimal entryPrice;
    private final Instant entryTime;
    private BigDecimal exitPrice;
    private Instant exitTime;
    private BigDecimal pnl;
    private BigDecimal pnlPercent;
    private int barCount;

    public Trade(String tradeId, Signal signal, BigDecimal entryPrice, Instant entryTime) {
        this.tradeId = tradeId;
        this.signal = signal;
        this.entryPrice = entryPrice;
        this.entryTime = entryTime;
        this.barCount = 1;
    }

    public void close(BigDecimal exitPrice, Instant exitTime) {
        this.exitPrice = exitPrice;
        this.exitTime = exitTime;
        calculatePnL();
    }

    private void calculatePnL() {
        if (exitPrice == null) {
            return;
        }

        if (signal == Signal.BUY) {
            pnl = exitPrice.subtract(entryPrice);
        } else if (signal == Signal.SELL) {
            pnl = entryPrice.subtract(exitPrice);
        } else {
            pnl = BigDecimal.ZERO;
        }

        if (entryPrice.compareTo(BigDecimal.ZERO) > 0) {
            pnlPercent = pnl.divide(entryPrice, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
    }

    public boolean isWinner() {
        return pnl != null && pnl.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isOpen() {
        return exitPrice == null;
    }

    // Getters
    public String getTradeId() { return tradeId; }
    public Signal getSignal() { return signal; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public Instant getEntryTime() { return entryTime; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public Instant getExitTime() { return exitTime; }
    public BigDecimal getPnl() { return pnl; }
    public BigDecimal getPnlPercent() { return pnlPercent; }
    public int getBarCount() { return barCount; }
    public void incrementBarCount() { this.barCount++; }

    @Override
    public String toString() {
        return String.format(
                "%s: %s @ %.2f â†’ %.2f | P&L: %s (%.2f%%)",
                tradeId, signal,
                entryPrice, exitPrice != null ? exitPrice : "OPEN",
                pnl != null ? pnl.setScale(2, RoundingMode.HALF_UP) : "N/A",
                pnlPercent != null ? pnlPercent.setScale(2, RoundingMode.HALF_UP) : "N/A"
        );
    }
}

