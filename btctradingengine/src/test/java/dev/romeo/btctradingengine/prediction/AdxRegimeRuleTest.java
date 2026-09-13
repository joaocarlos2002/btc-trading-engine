package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.prediction.rules.AdxRegimeRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AdxRegimeRuleTest {

    @Test
    public void warmupValuesDoNotVeto() {
        // Zero means the indicator has no value yet - the filter must stay neutral, not block
        assertEquals(0.0, new AdxRegimeRule().evaluate(features("0", "0")), 0.01);
    }

    @Test
    public void weakTrendVetoes() {
        double score = new AdxRegimeRule().evaluate(features("12", "2.0"));

        assertTrue(score < 0, "ADX below trend.min should veto");
        assertEquals(-0.3, score, 0.01);
    }

    @Test
    public void trendingMarketIsNeutral() {
        assertEquals(0.0, new AdxRegimeRule().evaluate(features("35", "2.0")), 0.01);
    }

    @Test
    public void squeezeVetoes() {
        // Trending, but the bands are compressed: the breakout has not happened yet
        double score = new AdxRegimeRule().evaluate(features("35", "0.2"));

        assertEquals(-0.2, score, 0.01);
    }

    @Test
    public void weakTrendAndSqueezeStayWithinTheFilterBand() {
        double score = new AdxRegimeRule().evaluate(features("12", "0.2"));

        // Penalties are not summed - the score must stay inside the [-0.3, 0] band the other
        // filter rules use, so no single filter can drown out the rest of the average
        assertEquals(-0.3, score, 0.01);
        assertTrue(score >= -0.3);
    }

    @Test
    public void honoursOverriddenThresholds() {
        AdxRegimeRule rule = new AdxRegimeRule(new BigDecimal("10"), new BigDecimal("0.1"));

        // ADX 12 is above the overridden trend.min and width 0.2 above the overridden squeeze
        assertEquals(0.0, rule.evaluate(features("12", "0.2")), 0.01);
    }

    private FeatureVector features(String adx, String bbWidth) {
        return FeatureVector.builder()
                .instrument("BTC/USD")
                .timestamp(Instant.now())
                .adx(new BigDecimal(adx))
                .bbWidth(new BigDecimal(bbWidth))
                .price(new BigDecimal("100"))
                .tickCount(10)
                .build();
    }
}
