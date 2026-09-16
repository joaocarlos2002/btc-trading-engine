package dev.romeo.btctradingengine.dashboard;

import dev.romeo.btctradingengine.backtest.BacktestParams;
import dev.romeo.btctradingengine.backtest.BacktestRequest;
import dev.romeo.btctradingengine.backtest.BacktestService;
import dev.romeo.btctradingengine.backtest.WalkForward;
import dev.romeo.btctradingengine.config.Config;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final DashboardState state;
    private final BacktestService backtests;

    public DashboardController(DashboardState state, BacktestService backtests) {
        this.state = state;
        this.backtests = backtests;
    }

    @GetMapping("/candles/latest")
    public ResponseEntity<?> latestCandle() { return state.latestCandle().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build()); }

    @GetMapping("/candles/history")
    public Object candleHistory(@RequestParam(defaultValue = "100") int limit) { return state.candles(limit); }

    @GetMapping("/metrics/current")
    public ResponseEntity<?> metrics() { return state.latestFeatures().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build()); }

    @GetMapping("/prediction/current")
    public ResponseEntity<?> prediction() { return state.latestPrediction().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build()); }

    @GetMapping("/trades/open")
    public ResponseEntity<?> openTrade() { return state.openPosition().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build()); }

    @PostMapping("/trades/manual/buy")
    public ResponseEntity<DashboardState.ManualBuyResult> manualBuy() {
        DashboardState.ManualBuyResult result = state.manualBuy();
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/trades/manual/close")
    public ResponseEntity<DashboardState.ManualBuyResult> manualClose() {
        DashboardState.ManualBuyResult result = state.manualClose();
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }

    @GetMapping("/trades/closed")
    public Object closedTrades(@RequestParam(defaultValue = "50") int limit) { return state.closedPositions(limit); }

    @GetMapping("/stats")
    public DashboardState.Stats stats() { return state.stats(); }

    /**
     * Runs the current strategy config against real Binance mainnet history and returns the report,
     * synchronously. Kept for the backtest page and scripts; every {@link BacktestParams} component
     * can be overridden by a query parameter of the same name (unknown parameters are ignored, as
     * before). Validated and limited like the jobs (issue #83): 400 with the error list, 429 while
     * another backtest runs. Prefer POST /api/backtests for long ranges.
     */
    @GetMapping("/backtest")
    public ResponseEntity<?> backtest(@RequestParam Map<String, String> query) {
        int days;
        try {
            days = Integer.parseInt(query.getOrDefault("days", "30").trim());
        } catch (NumberFormatException e) {
            return invalid(List.of("days must be a whole number"));
        }
        // The GET has always clamped the range instead of refusing it
        int clampedDays = Math.max(1, Math.min(days, BacktestRequest.MAX_DAYS));
        boolean sizeSplit = Boolean.parseBoolean(query.getOrDefault("sizeSplit", "false"));

        Map<String, String> overrides = new LinkedHashMap<>(query);
        overrides.keySet().retainAll(BacktestParams.parameterNames());
        BacktestParams params;
        try {
            params = BacktestParams.fromConfig().withOverrides(overrides);
        } catch (IllegalArgumentException e) {
            return invalid(List.of(e.getMessage().split("; ")));
        }
        return ResponseEntity.ok(backtests.runNow(BacktestRequest.single(clampedDays, sizeSplit, params)));
    }

    /**
     * Body of the POST /api/backtests* endpoints. {@code params} overrides the configured strategy by
     * BacktestParams component name; {@code param}/{@code values} name the swept field; the rest only
     * applies to walk-forward.
     */
    public record BacktestJobBody(
            Integer days,
            Boolean sizeSplit,
            Map<String, Object> params,
            String param,
            List<Object> values,
            Integer trainDays,
            Integer testDays,
            Integer holdOutDays,
            String objective,
            Integer minTrades,
            Integer warmupCandles
    ) { }

    /** Starts a single backtest as a job: 202 with the job, then poll GET /api/backtests/{id}. */
    @PostMapping("/backtests")
    public ResponseEntity<?> submitBacktest(@RequestBody(required = false) BacktestJobBody body) {
        BacktestJobBody b = body == null ? emptyBody() : body;
        List<String> errors = new ArrayList<>();
        BacktestParams params = params(b, errors);
        if (!errors.isEmpty()) return invalid(errors);
        return accepted(backtests.submit(BacktestRequest.single(days(b), bool(b.sizeSplit()), params)));
    }

    /** Runs one backtest per value of {@code param}, in parallel over candles downloaded once. */
    @PostMapping("/backtests/sweep")
    public ResponseEntity<?> submitSweep(@RequestBody BacktestJobBody body) {
        List<String> errors = new ArrayList<>();
        BacktestParams params = params(body, errors);
        if (!errors.isEmpty()) return invalid(errors);
        return accepted(backtests.submit(BacktestRequest.sweep(
                days(body), bool(body.sizeSplit()), params, body.param(), values(body))));
    }

    /**
     * Walk-forward over {@code param}: optimize on trainDays, validate on the next testDays, roll by
     * testDays, then evaluate the last holdOutDays once. objective is profitFactor (default),
     * returnPercent or sharpe; values with fewer than minTrades (default 5) trades are not eligible.
     * warmupCandles defaults to the larger of market.history.candles and the widest indicator window.
     */
    @PostMapping("/backtests/walk-forward")
    public ResponseEntity<?> submitWalkForward(@RequestBody BacktestJobBody body) {
        List<String> errors = new ArrayList<>();
        BacktestParams params = params(body, errors);
        WalkForward.Objective objective = WalkForward.Objective.parse(body.objective()).orElse(null);
        if (objective == null) {
            errors.add("objective must be profitFactor, returnPercent or sharpe");
        }
        if (body.trainDays() == null || body.testDays() == null) {
            errors.add("trainDays and testDays are required");
        }
        if (!errors.isEmpty()) return invalid(errors);

        int warmup = body.warmupCandles() != null
                ? body.warmupCandles()
                : Math.max(Config.getHistoryCandles(), params.warmupCandles());
        WalkForward.Settings settings = new WalkForward.Settings(body.trainDays(), body.testDays(),
                Objects.requireNonNullElse(body.holdOutDays(), 0), objective,
                Objects.requireNonNullElse(body.minTrades(), 5), warmup);
        return accepted(backtests.submit(BacktestRequest.walkForward(
                days(body), bool(body.sizeSplit()), params, body.param(), values(body), settings)));
    }

    @GetMapping("/backtests/{id}")
    public ResponseEntity<?> backtestJob(@PathVariable String id) {
        return backtests.job(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No backtest job " + id)));
    }

    @ExceptionHandler(BacktestService.InvalidRequestException.class)
    public ResponseEntity<?> onInvalidBacktest(BacktestService.InvalidRequestException e) {
        return invalid(e.errors());
    }

    @ExceptionHandler(BacktestService.BusyException.class)
    public ResponseEntity<?> onBusy(BacktestService.BusyException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "30")
                .body(Map.of("error", e.getMessage()));
    }

    /** The service already logged the cause; only its client-safe message goes out. */
    @ExceptionHandler(BacktestService.BacktestFailure.class)
    public ResponseEntity<?> onBacktestFailure(BacktestService.BacktestFailure e) {
        HttpStatus status = e.clientError() ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }

    private static ResponseEntity<?> invalid(List<String> errors) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid backtest parameters: " + String.join("; ", errors),
                "errors", errors));
    }

    private static ResponseEntity<?> accepted(BacktestService.JobView job) {
        return ResponseEntity.accepted().location(URI.create("/api/backtests/" + job.id())).body(job);
    }

    private static BacktestJobBody emptyBody() {
        return new BacktestJobBody(null, null, null, null, null, null, null, null, null, null, null);
    }

    private static BacktestParams params(BacktestJobBody body, List<String> errors) {
        BacktestParams defaults = BacktestParams.fromConfig();
        if (body.params() == null || body.params().isEmpty()) {
            return defaults;
        }
        try {
            return defaults.withOverrides(body.params());
        } catch (IllegalArgumentException e) {
            errors.addAll(List.of(e.getMessage().split("; ")));
            return defaults;
        }
    }

    private static int days(BacktestJobBody body) {
        return Objects.requireNonNullElse(body.days(), 30);
    }

    private static boolean bool(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private static List<String> values(BacktestJobBody body) {
        return body.values() == null ? List.of() : body.values().stream().map(String::valueOf).toList();
    }
}
