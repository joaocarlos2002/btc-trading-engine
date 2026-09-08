package dev.romeo.btctradingengine.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class BacktestReport {
    private final List<Trade> trades;
    private final BigDecimal initialCapital;
    private final BigDecimal commissionRate;

    public BacktestReport(List<Trade> trades, BigDecimal initialCapital, BigDecimal commissionRate) {
        this.trades = trades;
        this.initialCapital = initialCapital;
        this.commissionRate = commissionRate;
    }

    public int getTotalTrades() {
        return trades.size();
    }

    public int getWinTrades() {
        return (int) trades.stream().filter(this::isNetWinner).count();
    }

    public int getLoseTrades() {
        return getTotalTrades() - getWinTrades();
    }

    public BigDecimal getWinRate() {
        if (getTotalTrades() == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(getWinTrades())
                .divide(BigDecimal.valueOf(getTotalTrades()), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    public BigDecimal getTotalPnL() {
        return trades.stream()
                .filter(t -> t.getPnl() != null)
                .map(this::netPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getAveragePnL() {
        if (getTotalTrades() == 0) return BigDecimal.ZERO;
        return getTotalPnL().divide(BigDecimal.valueOf(getTotalTrades()), 6, RoundingMode.HALF_UP);
    }

    public BigDecimal getReturnPercent() {
        if (initialCapital.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return getTotalPnL().divide(initialCapital, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    public BigDecimal getProfitFactor() {
        BigDecimal grossProfit = trades.stream()
            .filter(this::isNetWinner)
                .filter(t -> t.getPnl() != null)
            .map(this::netPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossLoss = trades.stream()
            .filter(t -> !isNetWinner(t))
                .filter(t -> t.getPnl() != null)
            .map(t -> netPnl(t).abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (grossLoss.compareTo(BigDecimal.ZERO) == 0) {
            return grossProfit.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(999) : BigDecimal.ZERO;
        }

        return grossProfit.divide(grossLoss, 4, RoundingMode.HALF_UP);
    }

    public BigDecimal getMaxDrawdown() {
        if (trades.isEmpty()) return BigDecimal.ZERO;

        BigDecimal peak = initialCapital;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        BigDecimal currentEquity = initialCapital;

        for (Trade trade : trades) {
            if (trade.getPnl() != null) {
                currentEquity = currentEquity.add(trade.getPnl())
                        .subtract(calculateCommission(trade));
            }

            if (currentEquity.compareTo(peak) > 0) {
                peak = currentEquity;
            }

            BigDecimal drawdown = peak.subtract(currentEquity);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }

        return peak.compareTo(BigDecimal.ZERO) > 0
                ? maxDrawdown.divide(peak, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
    }

    public BigDecimal getSharpeRatio() {
        if (getTotalTrades() < 2) return BigDecimal.ZERO;

        BigDecimal avgReturn = getAveragePnL();
        BigDecimal variance = trades.stream()
                .filter(t -> t.getPnl() != null)
            .map(t -> netPnl(t).subtract(avgReturn).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(getTotalTrades()), 6, RoundingMode.HALF_UP);

        double stdDev = Math.sqrt(variance.doubleValue());
        if (stdDev == 0) return BigDecimal.ZERO;

        double riskFreeRate = 0.0;
        double sharpe = (avgReturn.doubleValue() - riskFreeRate) / stdDev;
        return BigDecimal.valueOf(sharpe).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal getAverageBarsPerTrade() {
        if (getTotalTrades() == 0) return BigDecimal.ZERO;
        int totalBars = trades.stream().mapToInt(Trade::getBarCount).sum();
        return BigDecimal.valueOf(totalBars)
                .divide(BigDecimal.valueOf(getTotalTrades()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateCommission(Trade trade) {
        if (trade.getEntryPrice() == null) return BigDecimal.ZERO;
        BigDecimal exitPrice = trade.getExitPrice() == null
            ? trade.getEntryPrice()
            : trade.getExitPrice();
        return trade.getEntryPrice()
            .add(exitPrice)
            .multiply(commissionRate);
    }

    private BigDecimal netPnl(Trade trade) {
        return trade.getPnl().subtract(calculateCommission(trade));
    }

    private boolean isNetWinner(Trade trade) {
        return trade.getPnl() != null && netPnl(trade).compareTo(BigDecimal.ZERO) > 0;
    }

    @Override
    public String toString() {
        return String.format(
                """
                === BACKTEST REPORT ===
                Total Trades: %d
                Win Trades: %d
                Lose Trades: %d
                Win Rate: %.2f%%

                Total P&L: %.2f
                Return: %.2f%%
                Avg P&L per trade: %.2f
                Profit Factor: %.2f

                Max Drawdown: %.2f%%
                Sharpe Ratio: %.4f
                Avg Bars per Trade: %.1f

                === END REPORT ===""",
                getTotalTrades(),
                getWinTrades(),
                getLoseTrades(),
                getWinRate(),
                getTotalPnL().setScale(2, RoundingMode.HALF_UP),
                getReturnPercent().setScale(2, RoundingMode.HALF_UP),
                getAveragePnL().setScale(2, RoundingMode.HALF_UP),
                getProfitFactor().setScale(2, RoundingMode.HALF_UP),
                getMaxDrawdown().setScale(2, RoundingMode.HALF_UP),
                getSharpeRatio(),
                getAverageBarsPerTrade()
        );
    }
}

