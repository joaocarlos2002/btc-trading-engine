package dev.romeo.btctradingengine.metrics;

import dev.romeo.btctradingengine.adapter.FeedStats;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.persistence.DatabaseWriter;
import dev.romeo.btctradingengine.persistence.TickRetentionJob;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.Signal;
import dev.romeo.btctradingengine.trading.ExecutionPort;
import dev.romeo.btctradingengine.trading.PortfolioManager;
import dev.romeo.btctradingengine.trading.PositionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Issue #104: the meters read the counters and state the pipeline beans already keep. */
class EngineMetricsTest {

    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FeedStats feed = new FeedStats();
    private final DatabaseWriter dbWriter = new DatabaseWriter(null);
    private final TickRetentionJob retention = TickRetentionJob.create(mock(DataSource.class), 7, 1000,
            Duration.ofMinutes(60));
    private final PositionManager positions = new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));

    private void bind() {
        new EngineMetrics(feed, dbWriter, retention, positions, new BigDecimal("5")).bindTo(registry);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double counter(String name) {
        return registry.get(name).functionCounter().count();
    }

    @Test
    void feedAndWriterMetersFollowTheBeans() {
        bind();
        NormalizedPriceEvent tick = new NormalizedPriceEvent("BTCUSDT", BigDecimal.TEN,
                NOW, NOW.plusMillis(80), BigDecimal.ONE);

        feed.record(tick);
        feed.record(tick);
        dbWriter.onEvent(tick);

        assertEquals(2, counter("feed.ticks.total"));
        assertEquals(80, gauge("feed.tick.lag.ms"));
        assertTrue(gauge("feed.tick.age.ms") >= 0);
        assertEquals(1, gauge("dbwriter.queue.size"));
        assertEquals(0, counter("dbwriter.dropped"));
        assertEquals(0, counter("ticks.retention.deleted"));
    }

    @Test
    void positionMetersReportFlatThenOpenWithItsUnrealizedPnl() {
        bind();
        assertEquals(0, gauge("position.open"));
        assertEquals(0, gauge("position.unrealized.pnl.pct"));
        assertTrue(Double.isNaN(gauge("portfolio.drawdown.pct")), "no portfolio in simulation");
        assertEquals(5, gauge("portfolio.drawdown.max.pct"));

        positions.processPrediction(prediction(Signal.BUY, true), candle("100"));
        positions.processPriceEvent(new NormalizedPriceEvent("BTCUSDT", new BigDecimal("101"), NOW, NOW));

        assertEquals(1, gauge("position.open"));
        assertEquals(1.0, gauge("position.unrealized.pnl.pct"), 1e-9);
    }

    @Test
    void drawdownComesFromTheRealModePortfolio() {
        positions.setRealTradingMode(mock(ExecutionPort.class), new PortfolioManager(new BigDecimal("1000"), new BigDecimal("5")), "BTCUSDT");
        positions.getPortfolioManager().orElseThrow().updateBalance(new BigDecimal("970"));
        bind();

        assertEquals(3.0, gauge("portfolio.drawdown.pct"), 1e-9);
    }

    @Test
    void blockedEntriesAreTaggedByReason() {
        bind();

        positions.processPrediction(prediction(Signal.SELL, true), candle("100"));
        positions.processPrediction(prediction(Signal.BUY, false), candle("100"));

        assertEquals(1, registry.get("prediction.entry.blocked").tag("reason", "short_disabled")
                .functionCounter().count());
        assertEquals(1, registry.get("prediction.entry.blocked").tag("reason", "filter")
                .functionCounter().count());
        assertEquals(0, registry.get("prediction.entry.blocked").tag("reason", "drawdown")
                .functionCounter().count());
        assertEquals(0, counter("orders.exit.failures"));
    }

    static PredictionVector prediction(Signal signal, boolean entryAllowed) {
        return PredictionVector.builder()
                .instrument("BTCUSDT")
                .timestamp(NOW)
                .signal(signal)
                .probabilityUp(new BigDecimal("0.5"))
                .probabilityDown(new BigDecimal("0.5"))
                .confidence(new BigDecimal("0.5"))
                .price(new BigDecimal("100"))
                .modelVersion("test")
                .entryAllowed(entryAllowed)
                .reason("test")
                .build();
    }

    private static CandleEvent candle(String close) {
        BigDecimal price = new BigDecimal(close);
        return new CandleEvent("BTCUSDT", NOW.minusSeconds(60), NOW.minusMillis(1), price, price, price, price,
                BigDecimal.ONE, 1);
    }
}
