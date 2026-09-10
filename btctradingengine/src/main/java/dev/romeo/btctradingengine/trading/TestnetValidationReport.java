package dev.romeo.btctradingengine.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agrega as posicoes fechadas de uma sessao (real ou testnet) para validar o
 * criterio da issue #4: volume minimo de trades, confirmacao de fills e
 * acuracia do P&amp;L antes de liberar producao.
 */
public class TestnetValidationReport {
    private final List<Position> closedPositions;

    public TestnetValidationReport(List<Position> closedPositions) {
        this.closedPositions = closedPositions;
    }

    public int getTotalTrades() {
        return closedPositions.size();
    }

    public int getWinTrades() {
        return (int) closedPositions.stream()
                .filter(p -> p.getPnL().compareTo(BigDecimal.ZERO) > 0)
                .count();
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

    /** Soma do P&amp;L por unidade de preco (mesma convencao do PositionManager). */
    public BigDecimal getTotalPnL() {
        return closedPositions.stream()
                .map(Position::getPnL)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** P&amp;L ponderado pela quantidade realmente preenchida em cada posicao. */
    public BigDecimal getTotalNotionalPnL() {
        return closedPositions.stream()
                .map(p -> p.getPnL().multiply(p.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getAveragePnL() {
        if (getTotalTrades() == 0) return BigDecimal.ZERO;
        return getTotalPnL().divide(BigDecimal.valueOf(getTotalTrades()), 6, RoundingMode.HALF_UP);
    }

    /** Numero de posicoes cujo fill nunca atingiu a quantidade alvo (fill parcial/zero). */
    public long getIncompleteFillCount() {
        return closedPositions.stream()
                .filter(p -> p.getTargetQuantity().compareTo(BigDecimal.ZERO) > 0)
                .filter(p -> !p.isFullyFilled())
                .count();
    }

    public Map<String, Long> getExitReasonBreakdown() {
        return closedPositions.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getExitReason() == null ? "UNKNOWN" : p.getExitReason(),
                        Collectors.counting()));
    }

    public Duration getAverageHoldingDuration() {
        List<Position> withTimes = closedPositions.stream()
                .filter(p -> p.getEntryTime() != null && p.getExitTime() != null)
                .toList();
        if (withTimes.isEmpty()) return Duration.ZERO;

        long totalSeconds = withTimes.stream()
                .mapToLong(p -> Duration.between(p.getEntryTime(), p.getExitTime()).getSeconds())
                .sum();
        return Duration.ofSeconds(totalSeconds / withTimes.size());
    }

    /** Criterio "100+ trades" da issue #4. */
    public boolean meetsMinimumTradeCount(int minTrades) {
        return getTotalTrades() >= minTrades;
    }

    /** Criterio "Fill confirmation check": nenhuma posicao deveria ficar com fill incompleto ao fechar. */
    public boolean allFillsConfirmed() {
        return getIncompleteFillCount() == 0;
    }

    @Override
    public String toString() {
        return String.format(
                """
                === TESTNET VALIDATION REPORT ===
                Total Trades: %d
                Win Trades: %d
                Lose Trades: %d
                Win Rate: %.2f%%

                Total P&L (per-unit): %.2f
                Total P&L (notional): %.2f
                Avg P&L per trade: %.2f
                Avg Holding Time: %s

                Incomplete Fills: %d
                Exit Reasons: %s

                === END REPORT ===""",
                getTotalTrades(),
                getWinTrades(),
                getLoseTrades(),
                getWinRate(),
                getTotalPnL().setScale(2, RoundingMode.HALF_UP),
                getTotalNotionalPnL().setScale(2, RoundingMode.HALF_UP),
                getAveragePnL().setScale(2, RoundingMode.HALF_UP),
                getAverageHoldingDuration(),
                getIncompleteFillCount(),
                getExitReasonBreakdown()
        );
    }
}
