package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.prediction.rules.AtrRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class AtrRuleTest {

    // Thresholds (0.15 / 0.35 / 0.55) are calibrated for the real 15m BTCUSDT ATR%
    // distribution (median ~0.24%, p95 ~0.54% over a 180-day sample) - not the 2/4/6%
    // used for daily candles, which the 15m ATR% never reaches.

    @Test
    public void lowAtrLowVolatility() {
        AtrRule rule = new AtrRule();

        // ATR = 0.1, Price = 100 â†’ ATR% = 0.1%
        FeatureVector features = createFeatures("100", "0.1");
        double score = rule.evaluate(features);

        assertTrue(score < 0, "Low ATR should reduce confidence");
        assertEquals(-0.3, score, 0.01);
    }

    @Test
    public void normalAtrNeutral() {
        AtrRule rule = new AtrRule();

        // ATR = 0.25, Price = 100 â†’ ATR% = 0.25%
        FeatureVector features = createFeatures("100", "0.25");
        double score = rule.evaluate(features);

        assertEquals(0.0, score, 0.01);
    }

    @Test
    public void moderateAtrPositive() {
        AtrRule rule = new AtrRule();

        // ATR = 0.45, Price = 100 â†’ ATR% = 0.45%
        FeatureVector features = createFeatures("100", "0.45");
        double score = rule.evaluate(features);

        assertTrue(score > 0 && score < 0.2, "Moderate ATR should be slightly positive");
        assertEquals(0.1, score, 0.01);
    }

    @Test
    public void highAtrBreakout() {
        AtrRule rule = new AtrRule();

        // ATR = 0.7, Price = 100 â†’ ATR% = 0.7%
        FeatureVector features = createFeatures("100", "0.7");
        double score = rule.evaluate(features);

        assertTrue(score > 0.1, "High ATR should be more positive (breakout potential)");
        assertEquals(0.2, score, 0.01);
    }

    private FeatureVector createFeatures(String price, String atr) {
        return FeatureVector.builder()
                .instrument("BTC/USD")
                .timestamp(Instant.now())
                .returnPct1m(new BigDecimal("0"))
                .volatility5m(new BigDecimal("2"))
                .volatility20m(new BigDecimal("1.5"))
                .smaDistance(new BigDecimal("0"))
                .emaDistance(new BigDecimal("0"))
                .rsiValue(new BigDecimal("50"))
                .macdValue(new BigDecimal("0"))
                .macdSignal(new BigDecimal("0"))
                .atrValue(new BigDecimal(atr))
                .volumeRatio(new BigDecimal("1.0"))
                .highLowRatio(new BigDecimal("2"))
                .closePosition(new BigDecimal("0.5"))
                .hourOfDay(10)
                .dayOfWeek(1)
                .price(new BigDecimal(price))
                .tickCount(40)
                .build();
    }
}


