package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.feature.DerivativesLookup;
import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Runs the same candles once per value of one parameter (issue #108). Pure CPU work: the candles and
 * the derivatives lookup are shared read-only across the runs, each run builds its own extractor,
 * predictor and engine. With an executor the runs go in parallel, without one they run in order on
 * the calling thread (what the unit tests use).
 */
public class ParameterSweep {
    private final BacktestRunner runner;
    private final ExecutorService executor;

    public ParameterSweep(BacktestRunner runner, ExecutorService executor) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.executor = executor;
    }

    /** One value of the swept parameter and what the backtest made of it. */
    public record Result(String value, BacktestReport report) { }

    /** Results in the order of {@code values}; every value must already be valid for {@code base}. */
    public List<Result> run(List<CandleEvent> warmup, List<CandleEvent> candles, DerivativesLookup derivatives,
                            BacktestParams base, String param, List<String> values, BigDecimal initialCapital)
            throws InterruptedException {
        List<BacktestParams> variants = values.stream().map(v -> base.with(param, v)).toList();
        List<Result> results = new ArrayList<>(values.size());

        if (executor == null) {
            for (int i = 0; i < values.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                results.add(new Result(values.get(i),
                        runner.run(warmup, candles, initialCapital, variants.get(i), derivatives)));
            }
            return results;
        }

        List<Future<BacktestReport>> futures = new ArrayList<>(values.size());
        for (BacktestParams variant : variants) {
            futures.add(executor.submit(() -> runner.run(warmup, candles, initialCapital, variant, derivatives)));
        }
        try {
            for (int i = 0; i < futures.size(); i++) {
                results.add(new Result(values.get(i), futures.get(i).get()));
            }
            return results;
        } catch (ExecutionException e) {
            throw new IllegalStateException("Sweep run failed for " + param, e.getCause());
        } catch (CancellationException | InterruptedException e) {
            futures.forEach(f -> f.cancel(true));
            throw e instanceof InterruptedException ie ? ie : new InterruptedException("sweep cancelled");
        } finally {
            futures.forEach(f -> f.cancel(true));
        }
    }
}
