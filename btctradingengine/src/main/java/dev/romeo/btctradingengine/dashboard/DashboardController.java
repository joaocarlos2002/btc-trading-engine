package dev.romeo.btctradingengine.dashboard;

import dev.romeo.btctradingengine.adapter.BinanceKlineClient;
import dev.romeo.btctradingengine.backtest.BacktestParams;
import dev.romeo.btctradingengine.backtest.BacktestReport;
import dev.romeo.btctradingengine.backtest.BacktestRunner;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.model.CandleEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final DashboardState state;
    private final BinanceKlineClient klineClient = new BinanceKlineClient();

    public DashboardController(DashboardState state) {
        this.state = state;
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
     * Runs the current strategy config (indicator periods, thresholds, target/stop) against
     * real Binance mainnet history and returns the resulting report - lets you re-validate
     * the strategy's edge on demand, e.g. after tuning thresholds or periodically over time.
     * Always uses mainnet data regardless of binance.rest.url, since testnet price/volume
     * does not reflect the real market.
     */
    @GetMapping("/backtest")
    public ResponseEntity<?> backtest(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) Integer smaPeriod,
            @RequestParam(required = false) Integer emaPeriod,
            @RequestParam(required = false) Integer rsiPeriod,
            @RequestParam(required = false) Double buyThreshold,
            @RequestParam(required = false) Double sellThreshold,
            @RequestParam(required = false) Integer confirmationSnapshots) {
        int clampedDays = Math.max(1, Math.min(days, 180));
        try {
            List<CandleEvent> candles = klineClient.loadClosedCandlesRange(
                    Config.getMarketSymbol(), Config.getBinanceKlineInterval(), clampedDays);
            if (candles.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "No candles returned for " + Config.getMarketSymbol() + " over " + clampedDays + " days"));
            }

            BacktestParams defaults = BacktestParams.fromConfig();
            BacktestParams params = new BacktestParams(
                    smaPeriod != null ? smaPeriod : defaults.smaPeriod(),
                    emaPeriod != null ? emaPeriod : defaults.emaPeriod(),
                    rsiPeriod != null ? rsiPeriod : defaults.rsiPeriod(),
                    buyThreshold != null ? buyThreshold : defaults.buyThreshold(),
                    sellThreshold != null ? sellThreshold : defaults.sellThreshold(),
                    confirmationSnapshots != null ? confirmationSnapshots : defaults.confirmationSnapshots());

            BacktestReport report = new BacktestRunner().run(candles, Config.getTradingInitialCapital(), params);
            return ResponseEntity.ok(Map.of(
                    "symbol", Config.getMarketSymbol(),
                    "interval", Config.getBinanceKlineInterval(),
                    "requestedDays", clampedDays,
                    "candleCount", candles.size(),
                    "rangeStart", candles.get(0).openTime(),
                    "rangeEnd", candles.get(candles.size() - 1).closeTime(),
                    "params", params,
                    "report", report
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Backtest failed: " + e.getMessage()));
        }
    }
}
