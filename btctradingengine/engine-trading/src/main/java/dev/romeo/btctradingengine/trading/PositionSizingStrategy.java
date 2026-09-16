package dev.romeo.btctradingengine.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * How much quote capital (USDT) a new entry uses (issue #111), replacing the fixed 50% of the balance.
 * A result of zero means "do not enter"; the quantity validation then rejects the order.
 */
@FunctionalInterface
public interface PositionSizingStrategy {

    BigDecimal allocate(SizingContext context);

    /**
     * @param atr latest ATR in price units; null when no candle has produced one yet
     * @param history closed performance trades, oldest first (failed entries excluded)
     */
    record SizingContext(BigDecimal quoteBalance, BigDecimal entryPrice, BigDecimal stopLossPercent,
                         BigDecimal atr, TradeStats history) {
    }

    /** Win rate and average win/loss in percent, from the closed trades Kelly learns from. */
    record TradeStats(int trades, BigDecimal winRate, BigDecimal averageWinPercent, BigDecimal averageLossPercent) {
        public static final TradeStats EMPTY = new TradeStats(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        public static TradeStats of(List<Position> closed) {
            List<BigDecimal> results = closed.stream()
                    .filter(ExitReason::isPerformanceTrade)
                    .map(Position::getPnLPercent)
                    .toList();
            if (results.isEmpty()) {
                return EMPTY;
            }
            List<BigDecimal> wins = results.stream().filter(r -> r.signum() > 0).toList();
            List<BigDecimal> losses = results.stream().filter(r -> r.signum() <= 0).map(BigDecimal::abs).toList();
            return new TradeStats(results.size(),
                    BigDecimal.valueOf(wins.size()).divide(BigDecimal.valueOf(results.size()), 8, RoundingMode.HALF_UP),
                    average(wins), average(losses));
        }

        private static BigDecimal average(List<BigDecimal> values) {
            return values.isEmpty() ? BigDecimal.ZERO
                    : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
        }
    }

    /** The historical behaviour: a fixed share of the quote balance. */
    static PositionSizingStrategy fixedFraction(BigDecimal fraction) {
        requireFraction("fraction", fraction);
        return context -> context.quoteBalance().multiply(fraction);
    }

    /**
     * Risks {@code riskFraction} of the balance between entry and a stop {@code atrMultiplier} ATRs
     * away: volatile markets get smaller positions. Without an ATR the configured stop-loss % is the
     * distance. Capped at {@code maxFraction} of the balance.
     */
    static PositionSizingStrategy atrRisk(BigDecimal riskFraction, BigDecimal atrMultiplier, BigDecimal maxFraction) {
        requireFraction("riskFraction", riskFraction);
        requireFraction("maxFraction", maxFraction);
        if (atrMultiplier.signum() <= 0) {
            throw new IllegalArgumentException("atrMultiplier must be positive");
        }
        return context -> {
            BigDecimal distance = context.atr() != null && context.atr().signum() > 0
                    ? context.atr().multiply(atrMultiplier)
                    : context.entryPrice().multiply(context.stopLossPercent()).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            BigDecimal cap = context.quoteBalance().multiply(maxFraction);
            if (distance.signum() <= 0) {
                return cap;
            }
            BigDecimal quantity = context.quoteBalance().multiply(riskFraction).divide(distance, 8, RoundingMode.DOWN);
            return quantity.multiply(context.entryPrice()).min(cap);
        };
    }

    /**
     * Fractional Kelly on the bot's own closed trades: f = p - (1 - p) / b with b = average win /
     * average loss, scaled by {@code kellyFraction} and capped at {@code maxFraction}. Until
     * {@code minTrades} trades exist the estimate is noise, so {@code fallback} sizes the entry.
     */
    static PositionSizingStrategy fractionalKelly(BigDecimal kellyFraction, int minTrades, BigDecimal maxFraction,
                                                  PositionSizingStrategy fallback) {
        requireFraction("kellyFraction", kellyFraction);
        requireFraction("maxFraction", maxFraction);
        return context -> {
            TradeStats stats = context.history();
            if (stats.trades() < minTrades) {
                return fallback.allocate(context);
            }
            if (stats.averageLossPercent().signum() == 0) {
                return context.quoteBalance().multiply(maxFraction); // no losing trade yet
            }
            if (stats.averageWinPercent().signum() == 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal payoff = stats.averageWinPercent().divide(stats.averageLossPercent(), 8, RoundingMode.HALF_UP);
            BigDecimal kelly = stats.winRate().subtract(
                    BigDecimal.ONE.subtract(stats.winRate()).divide(payoff, 8, RoundingMode.HALF_UP));
            BigDecimal fraction = kelly.multiply(kellyFraction).max(BigDecimal.ZERO).min(maxFraction);
            return context.quoteBalance().multiply(fraction);
        };
    }

    /** trading.sizing.strategy: fixed (default), atr or kelly. */
    static PositionSizingStrategy named(String name, BigDecimal fraction, BigDecimal atrRiskPercent,
                                        BigDecimal atrMultiplier, BigDecimal kellyFraction, int kellyMinTrades) {
        PositionSizingStrategy fixed = fixedFraction(fraction);
        return switch (name == null ? "fixed" : name.trim().toLowerCase(Locale.ROOT)) {
            case "", "fixed" -> fixed;
            case "atr" -> atrRisk(atrRiskPercent.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP),
                    atrMultiplier, fraction);
            case "kelly" -> fractionalKelly(kellyFraction, kellyMinTrades, fraction, fixed);
            default -> throw new IllegalArgumentException("Unknown trading.sizing.strategy: " + name);
        };
    }

    private static void requireFraction(String name, BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(name + " must be in (0, 1]");
        }
    }
}
