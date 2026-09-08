package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.prediction.rules.MacdRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class MacdRuleTest {

    @Test
    public void macdAboveSignalBullish() {
        MacdRule rule = new MacdRule();

        FeatureVector features = createFeatures("100", "0.5", "0.2");  // MACD > Signal
        double score = rule.evaluate(features);

        assertTrue(score > 0, "MACD above signal should be bullish");
        assertEquals(0.7, score, 0.01);
    }

    @Test
    public void macdBelowSignalBearish() {
        MacdRule rule = new MacdRule();

        FeatureVector features = createFeatures("100", "0.1", "0.5");  // MACD < Signal
        double score = rule.evaluate(features);

        assertTrue(score < 0, "MACD below signal should be bearish");
        assertEquals(-0.7, score, 0.01);
    }

    @Test
    public void macdWeaklyAboveSignal() {
        MacdRule rule = new MacdRule();

        FeatureVector features = createFeatures("100", "0.05", "0.03");  // Pequena diferenÃ§a
        double score = rule.evaluate(features);

        assertTrue(score > 0 && score < 0.7, "Weak bullish");
        assertEquals(0.3, score, 0.01);
    }

    @Test
    public void macdZeroReturnsNeutral() {
        MacdRule rule = new MacdRule();

        FeatureVector features = createFeatures("100", "0.0", "0.0");
        double score = rule.evaluate(features);

        assertEquals(0.0, score, 0.01);
    }

    private FeatureVector createFeatures(String price, String macd, String signal) {
        return FeatureVector.builder()
                .instrument("BTC/USD")
                .timestamp(Instant.now())
                .returnPct1m(new BigDecimal("0"))
                .volatility5m(new BigDecimal("2"))
                .volatility20m(new BigDecimal("1.5"))
                .smaDistance(new BigDecimal("0"))
                .emaDistance(new BigDecimal("0"))
                .rsiValue(new BigDecimal("50"))
                .macdValue(new BigDecimal(macd))
                .macdSignal(new BigDecimal(signal))
                .atrValue(new BigDecimal("1"))
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


