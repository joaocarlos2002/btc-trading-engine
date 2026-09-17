package dev.romeo.btctradingengine.benchmarks;

import dev.romeo.btctradingengine.feature.FeatureExtractor;
import dev.romeo.btctradingengine.feature.IndicatorPeriods;
import dev.romeo.btctradingengine.model.CandleEvent;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Cost of one candle through {@link FeatureExtractor} with the configured (x15 rescaled) periods, after the
 * indicators are warm (issue #110; the opt-in FeatureExtractorPerfTest is the rough end-to-end version).
 * The synthetic series is the same random walk as that test, so both numbers are comparable.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class FeatureExtractorBenchmark {

    /** Enough warmup for SMA(750), the longest default period. */
    private static final int WARMUP_CANDLES = 1_000;
    /** Candles per invocation (~1 week of 1m candles); the result is divided by it, so it reads per candle. */
    private static final int BATCH_CANDLES = 10_000;

    private List<CandleEvent> warmup;
    private List<CandleEvent> batch;
    private FeatureExtractor extractor;

    @Setup(Level.Trial)
    public void generateCandles() {
        List<CandleEvent> candles = syntheticCandles(WARMUP_CANDLES + BATCH_CANDLES);
        warmup = candles.subList(0, WARMUP_CANDLES);
        batch = candles.subList(WARMUP_CANDLES, candles.size());
    }

    /**
     * A fresh, warmed extractor per invocation: candles must move forward in time, so the same batch cannot be
     * replayed into one extractor. An invocation lasts tens of milliseconds, far above the Level.Invocation
     * timestamp overhead.
     */
    @Setup(Level.Invocation)
    public void warmExtractor(Blackhole blackhole) {
        extractor = new FeatureExtractor(IndicatorPeriods.defaults(), blackhole::consume);
        for (CandleEvent candle : warmup) {
            extractor.onEvent(candle);
        }
    }

    @Benchmark
    @OperationsPerInvocation(BATCH_CANDLES)
    public void onCandle() {
        for (CandleEvent candle : batch) {
            extractor.onEvent(candle);
        }
    }

    static List<CandleEvent> syntheticCandles(int n) {
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
