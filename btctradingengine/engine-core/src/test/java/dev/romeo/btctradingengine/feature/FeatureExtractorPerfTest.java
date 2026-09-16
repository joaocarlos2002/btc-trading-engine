package dev.romeo.btctradingengine.feature;

import dev.romeo.btctradingengine.model.CandleEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Rough throughput check for issue #95, not a benchmark: 180 days of synthetic 1m candles through
 * FeatureExtractor with the configured periods. Opt-in only:
 * {@code mvn test -Dperf=true -Dtest=FeatureExtractorPerfTest}.
 */
@EnabledIfSystemProperty(named = "perf", matches = "true")
public class FeatureExtractorPerfTest {

    private static final int CANDLES = 259_200;

    @Test
    public void processesHalfAYearOfMinuteCandles() {
        List<CandleEvent> candles = syntheticCandles(CANDLES);
        IndicatorPeriods periods = IndicatorPeriods.defaults();

        for (int run = 0; run < 3; run++) {
            long[] count = {0};
            FeatureExtractor extractor = new FeatureExtractor(periods, vector -> count[0]++);
            long start = System.nanoTime();
            for (CandleEvent candle : candles) {
                extractor.onEvent(candle);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("[perf] run %d: %d candles in %d ms (%.1f us/candle)%n",
                    run, count[0], elapsedMs, elapsedMs * 1000.0 / count[0]);
        }
    }

    private static List<CandleEvent> syntheticCandles(int n) {
        Random random = new Random(95);
        List<CandleEvent> candles = new ArrayList<>(n);
        double price = 60_000;
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < n; i++) {
            double open = price;
            double close = Math.max(1, open + random.nextGaussian() * 25);
            double high = Math.max(open, close) + Math.abs(random.nextGaussian()) * 10;
            double low = Math.min(open, close) - Math.abs(random.nextGaussian()) * 10;
            price = close;
            candles.add(new CandleEvent("BTCUSDT", t, t.plusSeconds(60),
                    scaled(open, 2), scaled(high, 2), scaled(low, 2), scaled(close, 2),
                    scaled(random.nextDouble() * 50, 5), 100));
            t = t.plusSeconds(60);
        }
        return candles;
    }

    private static BigDecimal scaled(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_EVEN);
    }
}
