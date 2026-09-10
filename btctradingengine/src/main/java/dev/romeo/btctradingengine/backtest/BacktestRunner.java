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
        Objects.requireNonNull(candles, "candles");
        Objects.requireNonNull(initialCapital, "initialCapital");
        if (candles.isEmpty()) {
            return new BacktestEngine(
                    Config.getBacktestCommissionRate(),
                    Config.getTradingTargetPercent(),
                    Config.getTradingStopLossPercent()
            ).generateReport(initialCapital);
        }

        BacktestEngine engine = BacktestEngine.configured();
        CandleEvent[] currentCandle = new CandleEvent[1];
        RuleBasedPredictor predictor = new RuleBasedPredictor(prediction ->
                engine.processPrediction(prediction, currentCandle[0]));
        predictor.addRule(new RsiRule());
        predictor.addRule(new SmaMomentumRule());
        predictor.addRule(new MacdRule());
        predictor.addFilterRule(new AtrRule());
        predictor.addFilterRule(new VolatilityRule());

        FeatureExtractor extractor = new FeatureExtractor(
                Config.getSmaPeriod(),
                Config.getEmaPeriod(),
                Config.getRsiPeriod(),
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

