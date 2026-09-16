package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.feature.FeatureVector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MarketRegimeClassifierTest {

    // trend.min 20, trend.strong 25, squeeze below a 0.5 band width
    private final MarketRegimeClassifier classifier =
            new MarketRegimeClassifier(new BigDecimal("20"), new BigDecimal("25"), new BigDecimal("0.5"));

    @Test
    public void warmupIsUnknown() {
        assertEquals(MarketRegime.UNKNOWN, classifier.classify(features("0", "0", "0", "0", "0")));
    }

    @Test
    public void compressedBandsAreASqueezeEvenWithAStrongAdx() {
        assertEquals(MarketRegime.SQUEEZE, classifier.classify(features("35", "30", "10", "0.2", "0.1")));
    }

    @Test
    public void lowAdxIsARange() {
        assertEquals(MarketRegime.RANGE, classifier.classify(features("12", "20", "18", "2.0", "0.1")));
    }

    @Test
    public void adxBetweenMinAndStrongIsATransition() {
        assertEquals(MarketRegime.TRANSITION, classifier.classify(features("22", "30", "10", "2.0", "0.1")));
    }

    @Test
    public void strongAdxWithDiAndSlopeAgreeingIsATrend() {
        assertEquals(MarketRegime.TREND_UP, classifier.classify(features("30", "30", "10", "2.0", "0.1")));
        assertEquals(MarketRegime.TREND_DOWN, classifier.classify(features("30", "10", "30", "2.0", "-0.1")));
    }

    @Test
    public void diAgainstTheEmaSlopeIsATransitionNotATrend() {
        // +DI leads but the EMA is still falling: usually the turn itself
        assertEquals(MarketRegime.TRANSITION, classifier.classify(features("30", "30", "10", "2.0", "-0.1")));
    }

    @Test
    public void regimesAllowTheRightFamilies() {
        assertFalse(MarketRegime.TREND_UP.allows(RuleFamily.MEAN_REVERSION));
        assertTrue(MarketRegime.TREND_UP.allows(RuleFamily.TREND));
        assertFalse(MarketRegime.RANGE.allows(RuleFamily.TREND));
        assertTrue(MarketRegime.RANGE.allows(RuleFamily.MEAN_REVERSION));
        assertTrue(MarketRegime.TRANSITION.allows(RuleFamily.TREND));
        assertTrue(MarketRegime.TRANSITION.allows(RuleFamily.MEAN_REVERSION));
        assertTrue(MarketRegime.SQUEEZE.allows(RuleFamily.OTHER));
    }

    private FeatureVector features(String adx, String plusDi, String minusDi, String bbWidth, String emaSlope) {
        return FeatureVector.builder()
                .instrument("BTC/USD")
                .timestamp(Instant.now())
                .adx(new BigDecimal(adx))
                .plusDi(new BigDecimal(plusDi))
                .minusDi(new BigDecimal(minusDi))
                .bbWidth(new BigDecimal(bbWidth))
                .emaSlope(new BigDecimal(emaSlope))
                .price(new BigDecimal("100"))
                .tickCount(10)
                .build();
    }
}
