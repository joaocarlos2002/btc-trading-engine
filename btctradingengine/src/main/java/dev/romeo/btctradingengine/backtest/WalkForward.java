package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.feature.DerivativesLookup;
import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Walk-forward optimization of one parameter (issue #108).
 *
 * <p>The range is cut into folds of {@code trainDays} followed by {@code testDays}, stepping by
 * {@code testDays}, so the test windows tile the range without overlapping and never overlap their
 * own train window. For every fold each candidate value is backtested on the train window, the best
 * one by the objective (among values with at least {@code minTrades} trades) is picked, and only that
 * value is run on the test window. The test runs are the out-of-sample result: their trades are pooled
 * into one report.
 *
 * <p>The folds are anchored at the end of the range (the last test window ends where the hold-out
 * begins), so the most recent data is always used and any leftover sits at the old end.
 *
 * <p><b>Hold-out:</b> the last {@code holdOutDays} are kept out of every fold and evaluated once at
 * the end, with the value chosen most often across the folds; a tie goes to the tied value chosen
 * most recently, since recent market conditions are the closest to the hold-out. If no fold chose a
 * value the hold-out is not run.
 *
 * <p><b>Warmup:</b> every train, test and hold-out run is preceded by up to {@code warmupCandles}
 * candles from right before its window, fed through the extractor without trading, so no window is
 * judged on indicators still warming up. Those candles only fill indicator state; nothing is selected
 * or traded on them, so borrowing them from the previous window does not leak.
 */
public class WalkForward {

    /**
     * Folds may start this much before the first candle. A range downloaded as "the last N days"
     * starts at the first whole candle after now - N days, a few seconds to a minute short of N days.
     */
    static final Duration EDGE_TOLERANCE = Duration.ofHours(1);

    public enum Objective {
        PROFIT_FACTOR, RETURN_PERCENT, SHARPE;

        public BigDecimal score(BacktestReport report) {
            return switch (this) {
                case PROFIT_FACTOR -> report.getProfitFactor();
                case RETURN_PERCENT -> report.getReturnPercent();
                case SHARPE -> report.getSharpeRatio();
            };
        }

        /** Accepts profitFactor / PROFIT_FACTOR / profit-factor. */
        public static Optional<Objective> parse(String text) {
            if (text == null || text.isBlank()) return Optional.of(PROFIT_FACTOR);
            String normalized = text.trim().replaceAll("[-_\\s]", "").toLowerCase(Locale.ROOT);
            for (Objective o : values()) {
                if (o.name().replace("_", "").toLowerCase(Locale.ROOT).equals(normalized)) return Optional.of(o);
            }
            return Optional.empty();
        }
    }

    public record Settings(int trainDays, int testDays, int holdOutDays, Objective objective, int minTrades,
                           int warmupCandles) {
        public Settings {
            Objects.requireNonNull(objective, "objective");
        }

        public List<String> validate(int totalDays) {
            List<String> errors = new ArrayList<>();
            if (trainDays < 1) errors.add("trainDays must be positive");
            if (testDays < 1) errors.add("testDays must be positive");
            if (holdOutDays < 0) errors.add("holdOutDays cannot be negative");
            if (minTrades < 0) errors.add("minTrades cannot be negative");
            if (warmupCandles < 0) errors.add("warmupCandles cannot be negative");
            if (errors.isEmpty() && trainDays + testDays + holdOutDays > totalDays) {
                errors.add("trainDays + testDays + holdOutDays (" + (trainDays + testDays + holdOutDays)
                        + ") must fit in days (" + totalDays + ")");
            }
            return errors;
        }

        /** Folds a range of {@code totalDays} yields - known before downloading anything. */
        public int expectedFolds(int totalDays) {
            int usable = totalDays - holdOutDays - trainDays;
            return usable < testDays ? 0 : usable / testDays;
        }
    }

    /** Half-open [start, end): a candle belongs to the window its openTime falls in. */
    public record Window(Instant start, Instant end) {
        public boolean contains(Instant t) {
            return !t.isBefore(start) && t.isBefore(end);
        }
    }

    public record Fold(int index, Window train, Window test) { }

    /** {@code holdOut} is null when no hold-out was asked for. */
    public record Plan(List<Fold> folds, Window holdOut) { }

    public record Candidate(String value, int trades, BigDecimal score, boolean qualified) { }

    public record FoldResult(int index, Window train, Window test, int trainCandles, int testCandles,
                             List<Candidate> candidates, String chosenValue, BigDecimal trainScore,
                             BacktestReport testReport) { }

    public record HoldOutResult(Window window, int candles, String chosenValue, String selectionRule,
                                BacktestReport report) { }

    public record Result(String param, List<String> values, Settings settings, List<FoldResult> folds,
                         int foldsWithoutChoice, BacktestReport outOfSample, HoldOutResult holdOut) { }

    public static final String HOLD_OUT_RULE =
            "most frequently chosen value across folds; ties go to the one chosen most recently";

    private final ParameterSweep sweep;
    private final BacktestRunner runner;

    public WalkForward(ParameterSweep sweep, BacktestRunner runner) {
        this.sweep = Objects.requireNonNull(sweep, "sweep");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * Folds for the range [rangeStart, rangeEnd). The hold-out is the last holdOutDays of the range;
     * folds are laid backwards from its start, one test window at a time, while their train window
     * still starts inside the range (within {@link #EDGE_TOLERANCE}). Returned in chronological order.
     */
    public static Plan plan(Instant rangeStart, Instant rangeEnd, Settings settings) {
        Duration train = Duration.ofDays(settings.trainDays());
        Duration test = Duration.ofDays(settings.testDays());
        Instant foldsEnd = rangeEnd.minus(Duration.ofDays(settings.holdOutDays()));
        Window holdOut = settings.holdOutDays() > 0 ? new Window(foldsEnd, rangeEnd) : null;

        List<Fold> reversed = new ArrayList<>();
        Instant testEnd = foldsEnd;
        while (true) {
            Instant testStart = testEnd.minus(test);
            Instant trainStart = testStart.minus(train);
            if (trainStart.isBefore(rangeStart.minus(EDGE_TOLERANCE))) break;
            reversed.add(new Fold(-1, new Window(trainStart, testStart), new Window(testStart, testEnd)));
            testEnd = testStart;
        }

        List<Fold> folds = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            Fold f = reversed.get(i);
            folds.add(new Fold(folds.size(), f.train(), f.test()));
        }
        return new Plan(List.copyOf(folds), holdOut);
    }

    /** Candles whose openTime falls in the window, in order. */
    public static List<CandleEvent> slice(List<CandleEvent> candles, Window window) {
        return candles.stream().filter(c -> window.contains(c.openTime())).toList();
    }

    /** Up to {@code count} candles opening right before {@code start}, in order. */
    public static List<CandleEvent> warmupBefore(List<CandleEvent> candles, Instant start, int count) {
        List<CandleEvent> before = candles.stream().filter(c -> c.openTime().isBefore(start)).toList();
        return before.subList(Math.max(0, before.size() - count), before.size());
    }

    /**
     * The best qualified value: highest score among values with at least minTrades trades, the first
     * listed value winning a tie. Empty when no value made enough trades.
     */
    public static Optional<Candidate> select(List<Candidate> candidates) {
        Candidate best = null;
        for (Candidate c : candidates) {
            if (c.qualified() && (best == null || c.score().compareTo(best.score()) > 0)) {
                best = c;
            }
        }
        return Optional.ofNullable(best);
    }

    public static List<Candidate> score(List<ParameterSweep.Result> results, Objective objective, int minTrades) {
        return results.stream()
                .map(r -> new Candidate(r.value(), r.report().getTotalTrades(), objective.score(r.report()),
                        r.report().getTotalTrades() >= minTrades))
                .toList();
    }

    /** See the class comment: most frequent, ties to the most recently chosen. Null for no choices. */
    public static String holdOutValue(List<String> chosenInFoldOrder) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> lastSeen = new LinkedHashMap<>();
        for (int i = 0; i < chosenInFoldOrder.size(); i++) {
            String v = chosenInFoldOrder.get(i);
            if (v == null) continue;
            counts.merge(v, 1, Integer::sum);
            lastSeen.put(v, i);
        }
        return counts.keySet().stream()
                .max(Comparator.<String>comparingInt(counts::get).thenComparingInt(lastSeen::get))
                .orElse(null);
    }

    public Result run(List<CandleEvent> candles, DerivativesLookup derivatives, BacktestParams base,
                      String param, List<String> values, Settings settings, BigDecimal initialCapital)
            throws InterruptedException {
        if (candles.isEmpty()) {
            throw new IllegalArgumentException("walk-forward needs candles");
        }
        Instant rangeStart = candles.get(0).openTime();
        // Binance closeTime is the last millisecond of the candle; the exclusive end is the one after it
        Instant rangeEnd = candles.get(candles.size() - 1).closeTime().plusMillis(1);
        Plan plan = plan(rangeStart, rangeEnd, settings);

        List<FoldResult> foldResults = new ArrayList<>();
        List<Trade> outOfSampleTrades = new ArrayList<>();
        List<String> chosen = new ArrayList<>();
        int withoutChoice = 0;

        for (Fold fold : plan.folds()) {
            List<CandleEvent> trainCandles = slice(candles, fold.train());
            List<CandleEvent> testCandles = slice(candles, fold.test());
            List<CandleEvent> trainWarmup = warmupBefore(candles, fold.train().start(), settings.warmupCandles());

            List<Candidate> candidates = score(
                    sweep.run(trainWarmup, trainCandles, derivatives, base, param, values, initialCapital),
                    settings.objective(), settings.minTrades());
            Optional<Candidate> best = select(candidates);

            BacktestReport testReport = null;
            if (best.isPresent()) {
                List<CandleEvent> testWarmup = warmupBefore(candles, fold.test().start(), settings.warmupCandles());
                testReport = runner.run(testWarmup, testCandles, initialCapital,
                        base.with(param, best.get().value()), derivatives);
                outOfSampleTrades.addAll(testReport.trades());
            } else {
                withoutChoice++;
            }
            chosen.add(best.map(Candidate::value).orElse(null));
            foldResults.add(new FoldResult(fold.index(), fold.train(), fold.test(), trainCandles.size(),
                    testCandles.size(), candidates, best.map(Candidate::value).orElse(null),
                    best.map(Candidate::score).orElse(null), testReport));
        }

        BacktestReport outOfSample = new BacktestReport(outOfSampleTrades, initialCapital, base.commissionRate());

        HoldOutResult holdOut = null;
        if (plan.holdOut() != null) {
            String value = holdOutValue(chosen);
            List<CandleEvent> holdOutCandles = slice(candles, plan.holdOut());
            BacktestReport report = null;
            if (value != null) {
                List<CandleEvent> warmup = warmupBefore(candles, plan.holdOut().start(), settings.warmupCandles());
                report = runner.run(warmup, holdOutCandles, initialCapital, base.with(param, value), derivatives);
            }
            holdOut = new HoldOutResult(plan.holdOut(), holdOutCandles.size(), value, HOLD_OUT_RULE, report);
        }

        return new Result(param, List.copyOf(values), settings, foldResults, withoutChoice, outOfSample, holdOut);
    }
}
