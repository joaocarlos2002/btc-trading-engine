package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class BacktestEngine {
    private static final Logger logger = LoggerFactory.getLogger(BacktestEngine.class);

    private final List<Trade> closedTrades = new ArrayList<>();
    private Optional<Trade> openTrade = Optional.empty();
    private final AtomicInteger tradeCounter = new AtomicInteger(0);
    private final BigDecimal commissionRate; // ex: 0.001 = 0.1%
    private final BigDecimal targetPercent;
    private final BigDecimal stopLossPercent;

    public BacktestEngine(BigDecimal commissionRate) {
        this(commissionRate, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public BacktestEngine(BigDecimal commissionRate, BigDecimal targetPercent, BigDecimal stopLossPercent) {
        this.commissionRate = commissionRate;
        this.targetPercent = targetPercent;
        this.stopLossPercent = stopLossPercent;
    }

    public static BacktestEngine configured() {
        Config.validate();
        return new BacktestEngine(
                Config.getBacktestCommissionRate(),
                Config.getTradingTargetPercent(),
                Config.getTradingStopLossPercent());
    }

    public void processPrediction(PredictionVector prediction, CandleEvent candle) {
        openTrade.ifPresent(Trade::incrementBarCount);

        Signal signal = prediction.signal();
        boolean closedByRisk = false;

        if (openTrade.isPresent()) {
            Trade current = openTrade.get();

            BigDecimal riskExitPrice = findRiskExitPrice(current, candle);
            if (riskExitPrice != null) {
                current.close(riskExitPrice, candle.closeTime());
                closedTrades.add(current);
                openTrade = Optional.empty();
                closedByRisk = true;
                logger.debug("Risk limit closed trade: {}", current);
            } else if (shouldCloseTrade(current, signal)) {
                current.close(candle.close(), candle.closeTime());
                closedTrades.add(current);
                openTrade = Optional.empty();
                logger.debug("Trade closed: {}", current);
            }
        }

        if (!closedByRisk && signal == Signal.BUY && !openTrade.isPresent()) {
            Trade trade = new Trade(
                    String.format("TRADE_%d", tradeCounter.incrementAndGet()),
                    Signal.BUY,
                    candle.close(),
                    candle.closeTime()
            );
            openTrade = Optional.of(trade);
            logger.debug("BUY signal: entered at {}", candle.close());

        } else if (!closedByRisk && signal == Signal.SELL && !openTrade.isPresent()) {
            Trade trade = new Trade(
                    String.format("TRADE_%d", tradeCounter.incrementAndGet()),
                    Signal.SELL,
                    candle.close(),
                    candle.closeTime()
            );
            openTrade = Optional.of(trade);
            logger.debug("SELL signal: entered at {}", candle.close());
        }
    }

    private BigDecimal findRiskExitPrice(Trade trade, CandleEvent candle) {
        if (targetPercent.compareTo(BigDecimal.ZERO) <= 0
                || stopLossPercent.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal targetRatio = targetPercent.divide(BigDecimal.valueOf(100), 8, java.math.RoundingMode.HALF_UP);
        BigDecimal stopRatio = stopLossPercent.divide(BigDecimal.valueOf(100), 8, java.math.RoundingMode.HALF_UP);

        if (trade.getSignal() == Signal.BUY) {
            BigDecimal target = trade.getEntryPrice().multiply(BigDecimal.ONE.add(targetRatio));
            BigDecimal stop = trade.getEntryPrice().multiply(BigDecimal.ONE.subtract(stopRatio));
            if (candle.low().compareTo(stop) <= 0) return stop;
            if (candle.high().compareTo(target) >= 0) return target;
        } else if (trade.getSignal() == Signal.SELL) {
            BigDecimal target = trade.getEntryPrice().multiply(BigDecimal.ONE.subtract(targetRatio));
            BigDecimal stop = trade.getEntryPrice().multiply(BigDecimal.ONE.add(stopRatio));
            if (candle.high().compareTo(stop) >= 0) return stop;
            if (candle.low().compareTo(target) <= 0) return target;
        }
        return null;
    }

    private boolean shouldCloseTrade(Trade trade, Signal newSignal) {
        if (trade.getSignal() == Signal.BUY && newSignal == Signal.SELL) {
            return true;
        }
        if (trade.getSignal() == Signal.SELL && newSignal == Signal.BUY) {
            return true;
        }

        return false;
    }

    public void finalize(BigDecimal lastPrice) {
        finalize(lastPrice, java.time.Instant.now());
    }

    public void finalize(BigDecimal lastPrice, java.time.Instant lastTime) {
        // Fechar trade aberto (se houver)
        if (openTrade.isPresent()) {
            Trade trade = openTrade.get();
            trade.close(lastPrice, lastTime);
            closedTrades.add(trade);
            logger.info("Final trade closed at: {}", lastPrice);
        }
    }

    public BacktestReport generateReport(BigDecimal initialCapital) {
        return new BacktestReport(closedTrades, initialCapital, commissionRate);
    }

    public List<Trade> getClosedTrades() {
        return new ArrayList<>(closedTrades);
    }

    public Optional<Trade> getOpenTrade() {
        return openTrade;
    }

    public int getTradeCount() {
        return closedTrades.size() + (openTrade.isPresent() ? 1 : 0);
    }
}

