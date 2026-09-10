package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.feature.FeatureExtractor;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.prediction.RuleBasedPredictor;
import dev.romeo.btctradingengine.prediction.rules.AtrRule;
import dev.romeo.btctradingengine.prediction.rules.MacdRule;
import dev.romeo.btctradingengine.prediction.rules.RsiRule;
import dev.romeo.btctradingengine.prediction.rules.SmaMomentumRule;
import dev.romeo.btctradingengine.prediction.rules.VolatilityRule;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public class BacktestRunner {
    public BacktestReport run(List<CandleEvent> candles, BigDecimal initialCapital) {
        return run(candles, initialCapital, BacktestParams.fromConfig());
    }

    public BacktestReport run(List<CandleEvent> candles, BigDecimal initialCapital, BacktestParams params) {
        Objects.requireNonNull(candles, "candles");
        Objects.requireNonNull(initialCapital, "initialCapital");
        Objects.requireNonNull(params, "params");
        if (candles.isEmpty()) {
            return new BacktestEngine(
                    Config.getBacktestCommissionRate(),
                    Config.getTradingTargetPercent(),
                    Config.getTradingStopLossPercent()
            ).generateReport(initialCapital);
        }

        BacktestEngine engine = new BacktestEngine(params.commissionRate(), params.targetPercent(), params.stopLossPercent());
        CandleEvent[] currentCandle = new CandleEvent[1];
        RuleBasedPredictor predictor = new RuleBasedPredictor(
                prediction -> engine.processPrediction(prediction, currentCandle[0]),
                params.buyThreshold(), params.sellThreshold(), params.confirmationSnapshots());
        predictor.addRule(new RsiRule(params.rsiOversold(), params.rsiNeutralLow(), params.rsiNeutralHigh(), params.rsiOverbought()));
        predictor.addRule(new SmaMomentumRule(params.smaDistanceExtreme(), params.smaDistanceModerate()));
        predictor.addRule(new MacdRule(params.macdStrongHistogramAtrRatio()));
        predictor.addFilterRule(new AtrRule(params.atrVolatilityLow(), params.atrVolatilityNormal(), params.atrVolatilityHigh()));
        predictor.addFilterRule(new VolatilityRule(params.volatilityRatioHigh()));

        FeatureExtractor extractor = new FeatureExtractor(
                params.smaPeriod(), params.emaPeriod(), params.rsiPeriod(), params.atrPeriod(),
                params.macdFastPeriod(), params.macdSlowPeriod(), params.macdSignalPeriod(),
                params.volatilityShortPeriods(), params.volatilityLongPeriods(), params.volumeAveragePeriods(),
                predictor::onEvent
        );

        for (CandleEvent candle : candles) {
            currentCandle[0] = candle;
            extractor.onEvent(candle);
        }

        CandleEvent lastCandle = candles.get(candles.size() - 1);
        engine.finalize(lastCandle.close(), lastCandle.closeTime());
        return engine.generateReport(initialCapital);
    }
}
