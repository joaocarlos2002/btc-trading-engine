package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class BacktestValidator {
    private static final Logger logger = LoggerFactory.getLogger(BacktestValidator.class);

    private final List<String> warnings = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    public void validateNoDataLeakage(FeatureVector features, PredictionVector prediction) {
        // Garantir que prediction usa apenas features disponÃ­veis
        if (prediction.timestamp().isAfter(features.timestamp())) {
            addError("Prediction timestamp after features timestamp (data leakage!)");
        }

        // Garantir que features nÃ£o tÃªm volatility sem histÃ³rico
        if (features.volatility5m().compareTo(BigDecimal.ZERO) == 0 &&
                features.volatility20m().compareTo(BigDecimal.ZERO) == 0) {
            addWarning("Volatility zero - may indicate insufficient historical data");
        }

        // Garantir que MACD tem valor (nÃ£o deve ser zero sem histÃ³rico)
        if (features.macdValue().compareTo(BigDecimal.ZERO) == 0 &&
                features.macdSignal().compareTo(BigDecimal.ZERO) == 0) {
            addWarning("MACD zero - may indicate insufficient historical data");
        }
    }

    public void validateCostsApplied(BigDecimal commission, BigDecimal totalPnL) {
        if (commission.compareTo(BigDecimal.ZERO) == 0) {
            addWarning("Commission is zero - backtests must include trading costs");
        }

        if (totalPnL.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal totalCost = commission;
            if (totalCost.compareTo(totalPnL) > 0) {
                addWarning("Total commission exceeds P&L (strategy unprofitable after costs)");
            }
        }
    }

    public void validateTradeSanity(Trade trade) {
        if (trade.getEntryPrice().compareTo(BigDecimal.ZERO) <= 0) {
            addError("Entry price must be positive: " + trade.getEntryPrice());
        }

        if (trade.isOpen()) {
            return;
        }

        if (trade.getExitPrice().compareTo(BigDecimal.ZERO) <= 0) {
            addError("Exit price must be positive: " + trade.getExitPrice());
        }

        if (trade.getExitTime().isBefore(trade.getEntryTime())) {
            addError("Exit time before entry time (temporal anomaly)");
        }
    }

    public void validateOutOfSample(int trainSize, int testSize, int totalSize) {
        if (trainSize < 0 || testSize < 0 || totalSize <= 0) {
            addError("Dataset sizes must be non-negative and total size must be positive");
            return;
        }

        if (trainSize + testSize != totalSize) {
            addError("Train + test size doesn't match total");
        }

        double trainPercent = (double) trainSize / totalSize;
        double testPercent = (double) testSize / totalSize;

        if (trainPercent < 0.6) {
            addWarning("Training set too small (<60% of data)");
        }

        if (testPercent < 0.2) {
            addWarning("Test set too small (<20% of data)");
        }

        logger.info("Train/Test split: {:.0f}% / {:.0f}%", trainPercent * 100, testPercent * 100);
    }

    public void validateDrawdown(BigDecimal maxDrawdown) {
        if (maxDrawdown.compareTo(BigDecimal.valueOf(50)) > 0) {
            addWarning("Max drawdown > 50% (extreme risk)");
        } else if (maxDrawdown.compareTo(BigDecimal.valueOf(30)) > 0) {
            addWarning("Max drawdown > 30% (high risk)");
        }
    }

    public void validateWinRate(BigDecimal winRate) {
        if (winRate.compareTo(BigDecimal.valueOf(30)) < 0) {
            addWarning("Win rate < 30% (low, needs high profit factor)");
        } else if (winRate.compareTo(BigDecimal.valueOf(70)) > 0) {
            addWarning("Win rate > 70% (suspiciously high, check for overfitting)");
        }
    }

    public void validateSharpeRatio(BigDecimal sharpe) {
        if (sharpe.compareTo(BigDecimal.ZERO) < 0) {
            addError("Negative Sharpe ratio (strategy underperforming)");
        } else if (sharpe.compareTo(BigDecimal.valueOf(0.5)) < 0) {
            addWarning("Sharpe ratio < 0.5 (poor risk-adjusted returns)");
        } else if (sharpe.compareTo(BigDecimal.valueOf(1.0)) > 0) {
            logger.info("Sharpe ratio > 1.0 (good risk-adjusted returns)");
        }
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    public void printReport() {
        if (errors.isEmpty() && warnings.isEmpty()) {
            logger.info("âœ“ Backtest validation: ALL CHECKS PASSED");
            return;
        }

        if (!errors.isEmpty()) {
            logger.error("âœ— ERRORS ({}):", errors.size());
            errors.forEach(e -> logger.error("  - {}", e));
        }

        if (!warnings.isEmpty()) {
            logger.warn("âš  WARNINGS ({}):", warnings.size());
            warnings.forEach(w -> logger.warn("  - {}", w));
        }
    }

    private void addError(String message) {
        errors.add(message);
    }

    private void addWarning(String message) {
        warnings.add(message);
    }

    public List<String> getErrors() {
        return new ArrayList<>(errors);
    }

    public List<String> getWarnings() {
        return new ArrayList<>(warnings);
    }
}

