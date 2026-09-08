package dev.romeo.btctradingengine.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class PortfolioManager {
    private static final Logger logger = LoggerFactory.getLogger(PortfolioManager.class);

    private final BigDecimal initialCapital;
    private final BigDecimal maxDrawdownPercent;  // ex: 10.0 = 10% max loss
    private volatile BigDecimal currentBalance;
    private volatile boolean tradingEnabled = true;

    public PortfolioManager(BigDecimal initialCapital, BigDecimal maxDrawdownPercent) {
        this.initialCapital = initialCapital;
        this.maxDrawdownPercent = maxDrawdownPercent;
        this.currentBalance = initialCapital;

        logger.info("Portfolio initialized: capital={} USDT, max_drawdown={}%", initialCapital, maxDrawdownPercent);
    }

    public synchronized void updateBalance(BigDecimal newBalance) {
        this.currentBalance = newBalance;

        BigDecimal drawdown = calculateDrawdownPercent();
        logger.info("Portfolio update: balance={} USDT | drawdown={}%", currentBalance, drawdown);

        if (drawdown.compareTo(maxDrawdownPercent) >= 0) {
            tradingEnabled = false;
            logger.warn("âœ— STOP-LOSS TRIGGERED: drawdown {}% >= max {}% | trading disabled",
                    drawdown, maxDrawdownPercent);
        } else if (drawdown.compareTo(maxDrawdownPercent.multiply(BigDecimal.valueOf(0.8))) < 0 && !tradingEnabled) {
            tradingEnabled = true;
            logger.info("âœ“ Drawdown recovered below 80% threshold | trading re-enabled");
        }
    }

    public BigDecimal calculateDrawdownPercent() {
        if (initialCapital.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return initialCapital.subtract(currentBalance)
                .divide(initialCapital, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    public boolean canTrade() {
        return tradingEnabled;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public BigDecimal getInitialCapital() {
        return initialCapital;
    }

    public BigDecimal getTotalPnL() {
        return currentBalance.subtract(initialCapital);
    }

    public BigDecimal getTotalPnLPercent() {
        if (initialCapital.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return getTotalPnL()
                .divide(initialCapital, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    public String getStatus() {
        return String.format(
                "Portfolio: %.2f USDT (initial: %.2f) | P&L: %s%% | drawdown: %s%% | trading: %s",
                currentBalance,
                initialCapital,
                getTotalPnLPercent().setScale(2, RoundingMode.HALF_UP),
                calculateDrawdownPercent().setScale(2, RoundingMode.HALF_UP),
                tradingEnabled ? "âœ“ ENABLED" : "âœ— DISABLED"
        );
    }
}

