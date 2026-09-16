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
        PredictionVector vetoed = predictions.get(1);
        // The veto blocks opening a position, but the BUY must survive: it is also what closes an open SELL
        assertEquals(Signal.BUY, vetoed.signal());
        assertFalse(vetoed.entryAllowed(), "A negative filter rule average must block a new entry");
        assertTrue(vetoed.reason().contains("filter rules"));
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
        assertTrue(predictions.get(1).entryAllowed());
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

    @Test
    public void regimeGatingDropsMeanReversionInATrend() {
        List<PredictionVector> predictions = new ArrayList<>();
        RuleBasedPredictor predictor = gatedPredictor(predictions::add, true);
        predictor.addRule(new RsiRule());                                 // mean reversion: RSI 25 -> +0.8
        predictor.addRule(new FixedScoreRule(0.5, RuleFamily.TREND));

        predictor.onEvent(trendUpFeatures());

        PredictionVector prediction = predictions.get(0);
        assertEquals(MarketRegime.TREND_UP, prediction.marketRegime());
        // RSI is off, so only the trend rule votes and the average is not diluted: 0.5, not 0.65
        assertEquals(0, prediction.confidence().compareTo(new BigDecimal("0.500")));
        assertTrue(prediction.reason().contains("=off"), "Gated rules must be visible in the reason");
    }

    @Test
    public void regimeGatingDropsTrendRulesInARange() {
        List<PredictionVector> predictions = new ArrayList<>();
        RuleBasedPredictor predictor = gatedPredictor(predictions::add, true);
        predictor.addRule(new RsiRule());
        predictor.addRule(new FixedScoreRule(-0.5, RuleFamily.TREND));

        FeatureVector range = FeatureVector.builder()
                .instrument("BTC/USD").timestamp(Instant.now())
                .rsiValue(new BigDecimal("25"))
                .adx(new BigDecimal("12")).plusDi(new BigDecimal("20")).minusDi(new BigDecimal("18"))
                .bbWidth(new BigDecimal("2.0"))
                .price(new BigDecimal("100")).tickCount(40)
                .build();
        predictor.onEvent(range);

        PredictionVector prediction = predictions.get(0);
        assertEquals(MarketRegime.RANGE, prediction.marketRegime());
        // only RSI votes
        assertEquals(0, prediction.confidence().compareTo(new BigDecimal("0.800")));
    }

    @Test
    public void withGatingOffTheRegimeIsReportedButEveryRuleVotes() {
        List<PredictionVector> predictions = new ArrayList<>();
        RuleBasedPredictor predictor = gatedPredictor(predictions::add, false);
        predictor.addRule(new RsiRule());
        predictor.addRule(new FixedScoreRule(0.5, RuleFamily.TREND));

        predictor.onEvent(trendUpFeatures());

        PredictionVector prediction = predictions.get(0);
        assertEquals(MarketRegime.TREND_UP, prediction.marketRegime());
        // (0.8 + 0.5) / 2
        assertEquals(0, prediction.confidence().compareTo(new BigDecimal("0.650")));
    }

    @Test
    public void highVpinBlocksEntriesWithoutErasingTheSignal() {
        List<PredictionVector> predictions = new ArrayList<>();
        RuleBasedPredictor predictor = new RuleBasedPredictor(predictions::add, 0.28, -0.28, 2,
                new MarketRegimeClassifier(new BigDecimal("20"), new BigDecimal("25"), new BigDecimal("0.5")),
                false, new VpinEntryGuard(true, new BigDecimal("0.35")));
        predictor.addRule(new RsiRule());

        FeatureVector toxic = FeatureVector.builder()
                .instrument("BTC/USD").timestamp(Instant.now())
                .rsiValue(new BigDecimal("25"))
                .vpin(new BigDecimal("0.5"))
                .price(new BigDecimal("100")).tickCount(40)
                .build();
        predictor.onEvent(toxic);
        predictor.onEvent(toxic);

        PredictionVector prediction = predictions.get(1);
        // the BUY survives so it can still close an open SELL on reversal; only opening is blocked
        assertEquals(Signal.BUY, prediction.signal());
        assertFalse(prediction.entryAllowed());
        assertTrue(prediction.reason().contains("VPIN"));
    }

    private RuleBasedPredictor gatedPredictor(PredictionEventListener listener, boolean gatingEnabled) {
        return new RuleBasedPredictor(listener, 0.28, -0.28, 2,
                new MarketRegimeClassifier(new BigDecimal("20"), new BigDecimal("25"), new BigDecimal("0.5")),
                gatingEnabled);
    }

    private FeatureVector trendUpFeatures() {
        return FeatureVector.builder()
                .instrument("BTC/USD").timestamp(Instant.now())
                .rsiValue(new BigDecimal("25"))
                .adx(new BigDecimal("30")).plusDi(new BigDecimal("30")).minusDi(new BigDecimal("10"))
                .bbWidth(new BigDecimal("2.0")).emaSlope(new BigDecimal("0.1"))
                .price(new BigDecimal("100")).tickCount(40)
                .build();
    }

    @Test
    public void singleSnapshotConfirmationEmitsOnTheFirstCandle() {
        List<PredictionVector> predictions = new ArrayList<>();
        RuleBasedPredictor predictor = new RuleBasedPredictor(predictions::add, 0.28, -0.28, 1);
        predictor.addRule(new FixedScoreRule(0.9));

        predictor.onEvent(createFeatures("100", "50"));

        // Before issue #88 N=1 still returned HOLD on the first candle and acted like N=2
        assertEquals(Signal.BUY, predictions.get(0).signal());
    }

    @Test
    public void confirmationNeedsExactlyNConsecutiveCandles() {
        List<PredictionVector> predictions = new ArrayList<>();
        RuleBasedPredictor predictor = new RuleBasedPredictor(predictions::add, 0.28, -0.28, 3);
        predictor.addRule(new FixedScoreRule(0.9));

        predictor.onEvent(createFeatures("100", "50"));
        predictor.onEvent(createFeatures("100", "50"));
        predictor.onEvent(createFeatures("100", "50"));
        predictor.onEvent(createFeatures("100", "50"));

        assertEquals(List.of(Signal.HOLD, Signal.HOLD, Signal.BUY, Signal.BUY),
                predictions.stream().map(PredictionVector::signal).toList());
    }

    @Test
    public void aHoldInBetweenRestartsTheConfirmation() {
        List<PredictionVector> predictions = new ArrayList<>();
        double[] score = { 0.9 };
        RuleBasedPredictor predictor = new RuleBasedPredictor(predictions::add, 0.28, -0.28, 2);
        predictor.addRule(new SignalRule() {
            @Override public double evaluate(FeatureVector features) { return score[0]; }
            @Override public String getName() { return "Variable"; }
        });

        predictor.onEvent(createFeatures("100", "50"));
        score[0] = 0.0;
        predictor.onEvent(createFeatures("100", "50"));
        score[0] = 0.9;
        predictor.onEvent(createFeatures("100", "50"));
        predictor.onEvent(createFeatures("100", "50"));

        assertEquals(List.of(Signal.HOLD, Signal.HOLD, Signal.HOLD, Signal.BUY),
                predictions.stream().map(PredictionVector::signal).toList());
    }

    @Test
    public void rejectsNonPositiveConfirmationSnapshots() {
        assertThrows(IllegalArgumentException.class, () -> new RuleBasedPredictor(p -> { }, 0.28, -0.28, 0));
        assertThrows(IllegalArgumentException.class, () -> new RuleBasedPredictor(p -> { }, 0.28, -0.28, -1));
    }

    @Test
    public void ruleNamesUseThePeriodTheyWereBuiltWith() {
        assertEquals("RSI(100)", new RsiRule(100, new BigDecimal("30"), new BigDecimal("40"),
                new BigDecimal("60"), new BigDecimal("70")).getName());
        assertEquals("SMA42-Distance", new SmaMomentumRule(42, new BigDecimal("2"), new BigDecimal("1")).getName());
        assertEquals("MFI(7)", new dev.romeo.btctradingengine.prediction.rules.MfiRule(7, new BigDecimal("20"),
                new BigDecimal("40"), new BigDecimal("60"), new BigDecimal("80")).getName());
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
        private final RuleFamily family;

        FixedScoreRule(double score) {
            this(score, RuleFamily.OTHER);
        }

        FixedScoreRule(double score, RuleFamily family) {
            this.score = score;
            this.family = family;
        }

        @Override
        public double evaluate(FeatureVector features) {
            return score;
        }

        @Override
        public RuleFamily family() {
            return family;
        }

        @Override
        public String getName() {
            return "Fixed(" + score + ")";
        }
    }
}


