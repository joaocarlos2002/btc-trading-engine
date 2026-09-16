package dev.romeo.btctradingengine.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class PortfolioManager {
    private static final Logger logger = LoggerFactory.getLogger(PortfolioManager.class);

    private volatile BigDecimal initialCapital;
    private final BigDecimal maxDrawdownPercent;  // ex: 10.0 = 10% max loss
    private volatile BigDecimal currentBalance;   // quote asset (USDT)
    private volatile boolean tradingEnabled = true;

    // Equity = USDT + base asset x last price (issue #81): buying must not look like a loss
    private volatile BigDecimal baseQuantity = BigDecimal.ZERO;
    private volatile BigDecimal lastPrice = null;
    // Base held at startup is added to the initial capital once the first price is known
    private BigDecimal pendingInitialBase = BigDecimal.ZERO;

    public PortfolioManager(BigDecimal initialCapital, BigDecimal maxDrawdownPercent) {
        this.initialCapital = initialCapital;
        this.maxDrawdownPercent = maxDrawdownPercent;
        this.currentBalance = initialCapital;

        logger.info("Portfolio initialized: capital={} USDT, max_drawdown={}%", initialCapital, maxDrawdownPercent);
    }

    /** Base asset already in the account when the portfolio is created. */
    public synchronized void setInitialBaseQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        baseQuantity = quantity;
        pendingInitialBase = quantity;
        foldPendingInitialBase();
    }

    public synchronized void updateBalance(BigDecimal newBalance) {
        this.currentBalance = newBalance;
        evaluateDrawdown(true);
    }

    /** Quote (USDT) and base asset totals, refreshed after each order. */
    public synchronized void updateBalances(BigDecimal quoteBalance, BigDecimal newBaseQuantity) {
        this.currentBalance = quoteBalance;
        this.baseQuantity = newBaseQuantity == null ? BigDecimal.ZERO : newBaseQuantity;
        evaluateDrawdown(true);
    }

    /** Marks the base asset to market; cheap enough to call on every tick (no I/O, no info logs). */
    public synchronized void updateMarketPrice(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        lastPrice = price;
        foldPendingInitialBase();
        if (baseQuantity.compareTo(BigDecimal.ZERO) > 0) {
            evaluateDrawdown(false);
        }
    }

    private void foldPendingInitialBase() {
        if (pendingInitialBase.compareTo(BigDecimal.ZERO) > 0 && lastPrice != null) {
            initialCapital = initialCapital.add(pendingInitialBase.multiply(lastPrice));
            pendingInitialBase = BigDecimal.ZERO;
            logger.info("Initial capital includes base asset held at startup: {} USDT", initialCapital);
        }
    }

    private void evaluateDrawdown(boolean logUpdate) {
        // Without a price the base asset cannot be valued: do not judge a half-known equity
        if (!equityKnown()) {
            if (logUpdate) {
                logger.info("Portfolio update: balance={} USDT, base qty={} | waiting for a price to value equity",
                        currentBalance, baseQuantity);
            }
            return;
        }

        BigDecimal drawdown = calculateDrawdownPercent();
        if (logUpdate) {
            logger.info("Portfolio update: balance={} USDT | equity={} USDT | drawdown={}%",
                    currentBalance, getEquity(), drawdown);
        }

        if (drawdown.compareTo(maxDrawdownPercent) >= 0) {
            if (!tradingEnabled) {
                return; // already stopped; ticks must not repeat the warning
            }
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
        return initialCapital.subtract(getEquity())
                .divide(initialCapital, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    public boolean canTrade() {
        return tradingEnabled;
    }

    private boolean equityKnown() {
        return pendingInitialBase.compareTo(BigDecimal.ZERO) == 0
                && (baseQuantity.compareTo(BigDecimal.ZERO) == 0 || lastPrice != null);
    }

    /** USDT plus the base asset valued at the last known price. */
    public BigDecimal getEquity() {
        BigDecimal price = lastPrice;
        BigDecimal base = baseQuantity;
        if (price == null || base.compareTo(BigDecimal.ZERO) == 0) {
            return currentBalance;
        }
        return currentBalance.add(base.multiply(price));
    }

    public BigDecimal getBaseQuantity() {
        return baseQuantity;
    }

    /** Free quote balance, used for position sizing. */
    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public BigDecimal getInitialCapital() {
        return initialCapital;
    }

    public BigDecimal getTotalPnL() {
        return getEquity().subtract(initialCapital);
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
                "Portfolio: %.2f USDT, equity %.2f USDT (initial: %.2f) | P&L: %s%% | drawdown: %s%% | trading: %s",
                currentBalance,
                getEquity(),
                initialCapital,
                getTotalPnLPercent().setScale(2, RoundingMode.HALF_UP),
                calculateDrawdownPercent().setScale(2, RoundingMode.HALF_UP),
                tradingEnabled ? "âœ“ ENABLED" : "âœ— DISABLED"
        );
    }
}

