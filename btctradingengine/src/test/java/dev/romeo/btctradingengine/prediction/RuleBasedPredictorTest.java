package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.prediction.rules.RsiRule;
import dev.romeo.btctradingengine.prediction.rules.SmaMomentumRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RuleBasedPredictorTest {

    @Test
    public void rsiRuleSignalsBuyOnOversold() {
        RsiRule rule = new RsiRule();

        FeatureVector features = createFeatures("100", "25");  // RSI = 25 (oversold)
        double score = rule.evaluate(features);

        assertTrue(score > 0, "Oversold RSI should give positive score");
        assertEquals(0.8, score, 0.01);
    }

    @Test
    public void rsiRuleSignalsSellOnOverbought() {
        RsiRule rule = new RsiRule();

        FeatureVector features = createFeatures("100", "75");  // RSI = 75 (overbought)
        double score = rule.evaluate(features);

        assertTrue(score < 0, "Overbought RSI should give negative score");
        assertEquals(-0.8, score, 0.01);
    }

    @Test
    public void rsiRuleNeutralInMiddle() {
        RsiRule rule = new RsiRule();

        FeatureVector features = createFeatures("100", "50");  // RSI = 50 (neutral)
        double score = rule.evaluate(features);

        assertEquals(0.0, score, 0.01);
    }

    @Test
    public void smaMomentumRuleBullishAboveSma() {
        SmaMomentumRule rule = new SmaMomentumRule();

        FeatureVector features = createFeatures("100", "50", smaDistance("2.0")); // 2% acima SMA
        double score = rule.evaluate(features);

        assertTrue(score > 0, "Price above SMA should be bullish");
    }

    @Test
    public void smaMomentumRuleBearishBelowSma() {
        SmaMomentumRule rule = new SmaMomentumRule();

        FeatureVector features = createFeatures("100", "50", smaDistance("-2.0")); // 2% abaixo SMA
        double score = rule.evaluate(features);

        assertTrue(score > 0, "Price moderately below SMA should be bullish (reversÃ£o)");
    }

    @Test
    public void predictorCombinesRules() {
        List<PredictionVector> predictions = new ArrayList<>();
        RuleBasedPredictor predictor = new RuleBasedPredictor(predictions::add);
        predictor.addRule(new RsiRule());
        predictor.addRule(new SmaMomentumRule());

        // RSI oversold + SMA distance bullish
        FeatureVector features = createFeatures("100", "25", smaDistance("-3.0"));
        predictor.onEvent(features);
        predictor.onEvent(features);

        assertEquals(2, predictions.size());
        assertEquals(Signal.HOLD, predictions.get(0).signal(), "First snapshot needs confirmation");
        PredictionVector pred = predictions.get(1);
        assertEquals(Signal.BUY, pred.signal(), "Strong buy signal");
        assertTrue(pred.confidence().compareTo(BigDecimal.ZERO) > 0, "Should have confidence");
        assertTrue(pred.probabilityUp().compareTo(pred.probabilityDown()) > 0, "Up should be more likely");
    }

    @Test
    public void filterRuleVetoesConfirmedSignal() {
        List<PredictionVector> predictions = new ArrayList<>();
        RuleBasedPredictor predictor = new RuleBasedPredictor(predictions::add);
        predictor.addRule(new RsiRule());
        predictor.addRule(new SmaMomentumRule());
        predictor.addFilterRule(new FixedScoreRule(-0.5));

        FeatureVector features = createFeatures("100", "25", smaDistance("-3.0"));
        predictor.onEvent(features);
        predictor.onEvent(features);

        assertEquals(2, predictions.size());
        assertEquals(Signal.HOLD, predictions.get(1).signal(),
                "A negative filter rule average must veto an otherwise-confirmed BUY");
    }

    @Test
    public void filterRuleAllowsSignalWhenNonNegative() {
        List<PredictionVector> predictions = new ArrayList<>();
        RuleBasedPredictor predictor = new RuleBasedPredictor(predictions::add);
        predictor.addRule(new RsiRule());
        predictor.addRule(new SmaMomentumRule());
        predictor.addFilterRule(new FixedScoreRule(0.1));

        FeatureVector features = createFeatures("100", "25", smaDistance("-3.0"));
        predictor.onEvent(features);
        predictor.onEvent(features);

        assertEquals(2, predictions.size());
        assertEquals(Signal.BUY, predictions.get(1).signal(),
                "A non-negative filter rule average must not block a confirmed BUY");
    }

    @Test
    public void predictorHoldOnConflictingSignals() {
        List<PredictionVector> predictions = new ArrayList<>();
        RuleBasedPredictor predictor = new RuleBasedPredictor(predictions::add);
        predictor.addRule(new RsiRule());
        predictor.addRule(new SmaMomentumRule());

        // RSI overbought but SMA distance still bullish
        FeatureVector features = createFeatures("100", "75", smaDistance("1.0"));
        predictor.onEvent(features);

        assertEquals(1, predictions.size());
        PredictionVector pred = predictions.get(0);
        // RSI contributes -0.8 and SMA is neutral, so the average score is -0.4.
        assertEquals(Signal.HOLD, pred.signal(), "One snapshot must not confirm a SELL");
        assertEquals(0, pred.confidence().compareTo(BigDecimal.valueOf(0.4)),
            "Confidence should reflect the average rule score");
    }

    @Test
    public void predictorPreservesInstrumentAndTimestamp() {
        List<PredictionVector> predictions = new ArrayList<>();
        RuleBasedPredictor predictor = new RuleBasedPredictor(predictions::add);
        predictor.addRule(new RsiRule());

        Instant now = Instant.parse("2026-09-08T10:15:00Z");
        FeatureVector features = createFeatures("100", "50");
        FeatureVector featured = FeatureVector.builder()
                .instrument("BTC/USD")
                .timestamp(now)
                .returnPct1m(new BigDecimal("5"))
                .volatility5m(new BigDecimal("2"))
                .volatility20m(new BigDecimal("1.5"))
                .smaDistance(new BigDecimal("1"))
                .emaDistance(new BigDecimal("0.5"))
                .rsiValue(new BigDecimal("50"))
                .volumeRatio(new BigDecimal("1.0"))
                .highLowRatio(new BigDecimal("2"))
                .closePosition(new BigDecimal("0.5"))
                .hourOfDay(10)
                .dayOfWeek(1)
                .price(new BigDecimal("100"))
                .tickCount(40)
                .build();

        predictor.onEvent(featured);

        PredictionVector pred = predictions.get(0);
        assertEquals("BTC/USD", pred.instrument());
        assertEquals(now, pred.timestamp());
    }

    private FeatureVector createFeatures(String price, String rsi) {
        return createFeatures(price, rsi, smaDistance("0.0"));
    }

    private FeatureVector createFeatures(String price, String rsi, BigDecimal smaDistance) {
        return FeatureVector.builder()
                .instrument("BTC/USD")
                .timestamp(Instant.now())
                .returnPct1m(new BigDecimal("0"))
                .volatility5m(new BigDecimal("2"))
                .volatility20m(new BigDecimal("1.5"))
                .smaDistance(smaDistance)
                .emaDistance(new BigDecimal("0"))
                .rsiValue(new BigDecimal(rsi))
                .volumeRatio(new BigDecimal("1.0"))
                .highLowRatio(new BigDecimal("2"))
                .closePosition(new BigDecimal("0.5"))
                .hourOfDay(10)
                .dayOfWeek(1)
                .price(new BigDecimal(price))
                .tickCount(40)
                .build();
    }

    private BigDecimal smaDistance(String value) {
        return new BigDecimal(value);
    }

    private static class FixedScoreRule implements SignalRule {
        private final double score;

        FixedScoreRule(double score) {
            this.score = score;
        }

        @Override
        public double evaluate(FeatureVector features) {
            return score;
        }

        @Override
        public String getName() {
            return "Fixed(" + score + ")";
        }
    }
}


