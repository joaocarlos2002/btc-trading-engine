package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.prediction.rules.MfiRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MfiRuleTest {

    // Thresholds (20 / 40 / 60 / 80) are the classic MFI levels, NOT calibrated on 1m BTCUSDT
    // data - see prediction.mfi.* in application.properties.

    @Test
    public void oversoldGivesStrongBuy() {
        double score = new MfiRule().evaluate(features("15"));

        assertEquals(0.8, score, 0.01);
    }

    @Test
    public void weakOversoldGivesMildBuy() {
        double score = new MfiRule().evaluate(features("35"));

        assertEquals(0.3, score, 0.01);
    }

    @Test
    public void neutralBandGivesZero() {
        assertEquals(0.0, new MfiRule().evaluate(features("50")), 0.01);
        assertEquals(0.0, new MfiRule().evaluate(features("40")), 0.01);
        assertEquals(0.0, new MfiRule().evaluate(features("60")), 0.01);
    }

    @Test
    public void warmupValueIsTreatedAsNeutral() {
        // MfiIndicator reports the neutral 50 while warming up, so the rule must not lean either way
        assertEquals(0.0, new MfiRule().evaluate(features("50")), 0.01);
    }

    @Test
    public void weakOverboughtGivesMildSell() {
        double score = new MfiRule().evaluate(features("70"));

        assertEquals(-0.3, score, 0.01);
    }

    @Test
    public void overboughtGivesStrongSell() {
        double score = new MfiRule().evaluate(features("90"));

        assertEquals(-0.8, score, 0.01);
    }

    @Test
    public void honoursOverriddenThresholds() {
        // Backtest constructor: with oversold at 45, an MFI of 40 becomes a strong BUY
        MfiRule rule = new MfiRule(
                new BigDecimal("45"), new BigDecimal("50"), new BigDecimal("55"), new BigDecimal("60"));

        assertTrue(rule.evaluate(features("40")) > 0.5);
    }

    private FeatureVector features(String mfi) {
        return FeatureVector.builder()
                .instrument("BTC/USD")
                .timestamp(Instant.now())
                .mfi(new BigDecimal(mfi))
                .price(new BigDecimal("100"))
                .tickCount(10)
                .build();
    }
}
