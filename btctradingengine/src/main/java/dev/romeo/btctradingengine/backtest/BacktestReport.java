package dev.romeo.btctradingengine.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class BacktestReport {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final List<Trade> trades;
    private final BigDecimal initialCapital;
    private final BigDecimal commissionRate;

    public BacktestReport(List<Trade> trades, BigDecimal initialCapital, BigDecimal commissionRate) {
        this.trades = trades;
        this.initialCapital = initialCapital;
        this.commissionRate = commissionRate;
    }

    /**
     * The trades behind the metrics, so walk-forward can pool out-of-sample trades into one report.
     * Deliberately not a getter: the report is serialized as JSON and the metrics are what goes out.
     */
    public List<Trade> trades() {
        return List.copyOf(trades);
    }

    public BigDecimal commissionRate() {
        return commissionRate;
    }

    public BigDecimal initialCapital() {
        return initialCapital;
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

    /**
     * USDT gained or lost by the capital after compounding every trade (issue #72). Trade.pnl is in
     * USDT per 1 BTC, so it used to be summed straight against a 100 USDT capital and a single
     * +1,800 trade read as +1,800%. Each trade now contributes its net return on the whole equity.
     */
    public BigDecimal getTotalPnL() {
        return getFinalEquity().subtract(initialCapital);
    }

    /** Capital after compounding the net return of every closed trade, fully allocated. */
    public BigDecimal getFinalEquity() {
        BigDecimal equity = initialCapital;
        for (BigDecimal r : netReturns()) {
            equity = equity.multiply(BigDecimal.ONE.add(r));
        }
        return equity.setScale(8, RoundingMode.HALF_UP);
    }

    public BigDecimal getAveragePnL() {
        if (getTotalTrades() == 0) return BigDecimal.ZERO;
        return getTotalPnL().divide(BigDecimal.valueOf(getTotalTrades()), 6, RoundingMode.HALF_UP);
    }

    /** Mean net return per trade, in % of the entry price. */
    public BigDecimal getAverageReturnPercent() {
        List<BigDecimal> returns = netReturns();
        if (returns.isEmpty()) return BigDecimal.ZERO;
        return returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), 10, RoundingMode.HALF_UP)
                .multiply(HUNDRED).setScale(6, RoundingMode.HALF_UP);
    }

    public BigDecimal getReturnPercent() {
        if (initialCapital.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return getTotalPnL().divide(initialCapital, 8, RoundingMode.HALF_UP)
                .multiply(HUNDRED).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * Sum of winning net returns over the sum of losing ones. Returns instead of USDT per BTC, so a
     * trade at 60k and one at 100k weigh by how much they made, not by the price level.
     */
    public BigDecimal getProfitFactor() {
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;
        for (BigDecimal r : netReturns()) {
            if (r.signum() > 0) {
                grossProfit = grossProfit.add(r);
            } else {
                grossLoss = grossLoss.add(r.abs());
            }
        }

        if (grossLoss.compareTo(BigDecimal.ZERO) == 0) {
            return grossProfit.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(999) : BigDecimal.ZERO;
        }

        return grossProfit.divide(grossLoss, 4, RoundingMode.HALF_UP);
    }

    /** Largest peak-to-trough fall of the compounded equity curve, in % of the peak. */
    public BigDecimal getMaxDrawdown() {
        BigDecimal peak = initialCapital;
        BigDecimal equity = initialCapital;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (BigDecimal r : netReturns()) {
            equity = equity.multiply(BigDecimal.ONE.add(r));
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            } else if (peak.signum() > 0) {
                BigDecimal drawdown = peak.subtract(equity).divide(peak, 10, RoundingMode.HALF_UP);
                maxDrawdown = maxDrawdown.max(drawdown);
            }
        }

        return maxDrawdown.multiply(HUNDRED).setScale(6, RoundingMode.HALF_UP);
    }

    /** Per-trade Sharpe (not annualized) over the net returns, risk-free rate zero. */
    public BigDecimal getSharpeRatio() {
        List<BigDecimal> returns = netReturns();
        if (returns.size() < 2) return BigDecimal.ZERO;

        double mean = returns.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double variance = returns.stream()
                .mapToDouble(r -> Math.pow(r.doubleValue() - mean, 2))
                .sum() / returns.size();

        double stdDev = Math.sqrt(variance);
        if (stdDev == 0) return BigDecimal.ZERO;

        return BigDecimal.valueOf(mean / stdDev).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal getAverageBarsPerTrade() {
        if (getTotalTrades() == 0) return BigDecimal.ZERO;
        int totalBars = trades.stream().mapToInt(Trade::getBarCount).sum();
        return BigDecimal.valueOf(totalBars)
                .divide(BigDecimal.valueOf(getTotalTrades()), 2, RoundingMode.HALF_UP);
    }

    /** Net return of each closed trade in the order they closed; open trades have no return yet. */
    private List<BigDecimal> netReturns() {
        return trades.stream()
                .filter(t -> t.getPnl() != null)
                .map(this::netReturn)
                .toList();
    }

    /** pnl/entry minus the commission paid on both legs, as a fraction of the entry price. */
    private BigDecimal netReturn(Trade trade) {
        BigDecimal entry = trade.getEntryPrice();
        if (entry == null || entry.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal exit = trade.getExitPrice() == null ? entry : trade.getExitPrice();
        BigDecimal commission = entry.add(exit).multiply(commissionRate);
        return trade.getPnl().subtract(commission).divide(entry, 12, RoundingMode.HALF_UP);
    }

    private boolean isNetWinner(Trade trade) {
        return trade.getPnl() != null && netReturn(trade).signum() > 0;
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
                Avg return per trade: %.4f%%
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
                getAverageReturnPercent(),
                getProfitFactor().setScale(2, RoundingMode.HALF_UP),
                getMaxDrawdown().setScale(2, RoundingMode.HALF_UP),
                getSharpeRatio(),
                getAverageBarsPerTrade()
        );
    }
}

