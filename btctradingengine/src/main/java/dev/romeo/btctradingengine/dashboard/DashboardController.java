package dev.romeo.btctradingengine.dashboard;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final DashboardState state;

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
}
