package dev.romeo.btctradingengine.backtest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * One backtest to run (issue #108): a single run, a sweep of one parameter, or a walk-forward over one
 * parameter. {@link #validate()} checks everything that can be checked before downloading candles.
 */
public record BacktestRequest(
        Kind kind,
        int days,
        boolean sizeSplit,
        BacktestParams params,
        String param,
        List<String> values,
        WalkForward.Settings walkForward
) {
    public enum Kind { SINGLE, SWEEP, WALK_FORWARD }

    /** Same ceiling GET /api/backtest has always clamped to. */
    public static final int MAX_DAYS = 180;
    public static final int MAX_SWEEP_VALUES = 50;
    /** Train runs (folds x values) plus one test run per fold; bounds the CPU one job can take. */
    public static final int MAX_WALK_FORWARD_RUNS = 600;

    public BacktestRequest {
        Objects.requireNonNull(kind, "kind");
        values = values == null ? List.of() : List.copyOf(values);
    }

    public static BacktestRequest single(int days, boolean sizeSplit, BacktestParams params) {
        return new BacktestRequest(Kind.SINGLE, days, sizeSplit, params, null, List.of(), null);
    }

    public static BacktestRequest sweep(int days, boolean sizeSplit, BacktestParams params,
                                        String param, List<String> values) {
        return new BacktestRequest(Kind.SWEEP, days, sizeSplit, params, param, values, null);
    }

    public static BacktestRequest walkForward(int days, boolean sizeSplit, BacktestParams params,
                                              String param, List<String> values, WalkForward.Settings settings) {
        return new BacktestRequest(Kind.WALK_FORWARD, days, sizeSplit, params, param, values, settings);
    }

    /** Every problem with the request, empty when it can run (issue #83). */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (days < 1 || days > MAX_DAYS) {
            errors.add("days must be between 1 and " + MAX_DAYS);
        }
        if (params == null) {
            errors.add("params are required");
            return errors;
        }
        errors.addAll(params.validate());
        if (kind == Kind.SINGLE) {
            return errors;
        }

        if (param == null || param.isBlank()) {
            errors.add("param is required");
        } else if (!BacktestParams.parameterNames().contains(param)) {
            errors.add("unknown parameter '" + param + "'");
        } else if (values.isEmpty()) {
            errors.add("values must list at least one value for " + param);
        } else if (values.size() > MAX_SWEEP_VALUES) {
            errors.add("values can list at most " + MAX_SWEEP_VALUES + " values");
        } else {
            if (new HashSet<>(values).size() != values.size()) {
                errors.add("values must not repeat");
            }
            for (String value : values) {
                try {
                    params.with(param, value).validate()
                            .forEach(e -> errors.add(param + "=" + value + ": " + e));
                } catch (IllegalArgumentException e) {
                    errors.add(e.getMessage());
                }
            }
        }

        if (kind == Kind.WALK_FORWARD) {
            if (walkForward == null) {
                errors.add("walk-forward settings are required");
            } else {
                List<String> settingsErrors = walkForward.validate(days);
                errors.addAll(settingsErrors);
                if (settingsErrors.isEmpty()) {
                    int folds = walkForward.expectedFolds(days);
                    if (folds < 1) {
                        errors.add("days leave room for no fold of trainDays + testDays");
                    } else if ((long) folds * (Math.max(1, values.size()) + 1) > MAX_WALK_FORWARD_RUNS) {
                        errors.add("walk-forward would take " + folds * (values.size() + 1) + " runs; at most "
                                + MAX_WALK_FORWARD_RUNS + " (fewer values, longer testDays or fewer days)");
                    }
                }
            }
        }
        return errors;
    }
}
