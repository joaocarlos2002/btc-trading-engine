package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.feature.DerivativesLookup;
import dev.romeo.btctradingengine.feature.FeatureExtractor;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.prediction.MarketRegimeClassifier;
import dev.romeo.btctradingengine.prediction.RuleBasedPredictor;
import dev.romeo.btctradingengine.prediction.VpinEntryGuard;
import dev.romeo.btctradingengine.prediction.rules.AdxRegimeRule;
import dev.romeo.btctradingengine.prediction.rules.AtrRule;
import dev.romeo.btctradingengine.prediction.rules.MacdRule;
import dev.romeo.btctradingengine.prediction.rules.MfiRule;
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
        return run(candles, initialCapital, params, DerivativesLookup.NONE);
    }

    public BacktestReport run(List<CandleEvent> candles, BigDecimal initialCapital, BacktestParams params,
                              DerivativesLookup derivatives) {
        return run(List.of(), candles, initialCapital, params, derivatives);
    }

    /**
     * Runs {@code candles} after feeding {@code warmup} through the extractor without trading (issue
     * #108). The warmup candles only fill the indicators, the same way the live bot warms up from
     * history before its first prediction: the predictor never sees them, so they can neither open
     * a trade nor count towards the signal confirmation. A walk-forward test window uses this so its
     * first candles are not judged on indicators still at their neutral warmup values.
     */
    public BacktestReport run(List<CandleEvent> warmup, List<CandleEvent> candles, BigDecimal initialCapital,
                              BacktestParams params, DerivativesLookup derivatives) {
        Objects.requireNonNull(warmup, "warmup");
        Objects.requireNonNull(candles, "candles");
        Objects.requireNonNull(initialCapital, "initialCapital");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(derivatives, "derivatives");
        if (candles.isEmpty()) {
            return new BacktestEngine(params.commissionRate(), params.targetPercent(),
                    params.stopLossPercent(), params.allowShort()).generateReport(initialCapital);
        }

        BacktestEngine engine = new BacktestEngine(params.commissionRate(), params.targetPercent(),
                params.stopLossPercent(), params.allowShort());
        CandleEvent[] currentCandle = new CandleEvent[1];
        RuleBasedPredictor predictor = new RuleBasedPredictor(
                prediction -> engine.processPrediction(prediction, currentCandle[0]),
                params.buyThreshold(), params.sellThreshold(), params.confirmationSnapshots(),
                new MarketRegimeClassifier(params.adxTrendMin(), params.adxTrendStrong(), params.bollingerSqueezeThreshold()),
                params.regimeGatingEnabled(),
                new VpinEntryGuard(params.vpinFilterEnabled(), params.vpinHighThreshold()));
        predictor.addRule(new RsiRule(params.rsiPeriod(), params.rsiOversold(), params.rsiNeutralLow(), params.rsiNeutralHigh(), params.rsiOverbought()));
        predictor.addRule(new SmaMomentumRule(params.smaPeriod(), params.smaDistanceExtreme(), params.smaDistanceModerate()));
        predictor.addRule(new MacdRule(params.macdStrongHistogramAtrRatio()));
        predictor.addRule(new MfiRule(params.mfiPeriod(), params.mfiOversold(), params.mfiNeutralLow(), params.mfiNeutralHigh(), params.mfiOverbought()));
        predictor.addFilterRule(new AtrRule(params.atrVolatilityLow(), params.atrVolatilityNormal(), params.atrVolatilityHigh()));
        predictor.addFilterRule(new VolatilityRule(params.volatilityRatioHigh()));
        predictor.addFilterRule(new AdxRegimeRule(params.adxTrendMin(), params.bollingerSqueezeThreshold(), params.regimeGatingEnabled()));

        FeatureExtractor extractor = new FeatureExtractor(params.indicatorPeriods(), derivatives, predictor::onEvent);

        for (CandleEvent candle : warmup) {
            extractor.warmUp(candle, features -> { });
        }
        for (CandleEvent candle : candles) {
            currentCandle[0] = candle;
            extractor.onEvent(candle);
        }

        CandleEvent lastCandle = candles.get(candles.size() - 1);
        engine.finalize(lastCandle.close(), lastCandle.closeTime());
        return engine.generateReport(initialCapital);
    }
}
